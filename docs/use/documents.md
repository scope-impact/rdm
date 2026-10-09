# Authoring and rendering documents

RDM's core pipeline turns **YAML data + Jinja2 Markdown templates** into
regulatory documents:

```
data/*.yml  +  documents/*.md (Jinja2)  →  rdm render  →  Markdown  →  Pandoc/Typst → PDF/DOCX
```

## Rendering

```bash
rdm render <template.md> <config.yml> [data files…] > output.md
```

The template path is relative to the working directory (run it from the
project root, as the Makefile does). A value the template uses but no data
file gives, or a template that does not exist, is an error naming it.

- `config.yml` configures rendering: the Markdown extensions to run over
  the rendered document (below). An empty file loads none.
- Each data file becomes a template variable named after its **file stem**:
  `data/history.yml` is `{{ history }}` in the template, so a top-level
  `entries:` list in that file is `history.entries`.

```markdown
---
id: SOP-001
revision: 2
title: "My controlled document"
---

# Revision history
{% for entry in history.entries %}
| {{ entry.revision }} | {{ entry.date }} | {{ entry.change }} |
{%- endfor %}
```

## Markdown extensions, and the auditor's and engineer's copies

`md_extensions` in `config.yml` lists the extensions, run in order over the
rendered lines; code is never touched:

| Extension | What it does |
|---|---|
| `rdm.md_extensions.SectionNumberExtension` | numbers the headings (`# 1`, `## 1.1`) |
| `rdm.md_extensions.VocabularyExtension` | gives the template the words the document uses, for a glossary or acronym list of only those terms |
| `rdm.md_extensions.AuditNoteExclusionExtension` | removes every `[[…]]` note, the checklist references among them |

The `[[62304:5.1.1]]` references are what [gap analysis](gap-analysis.md)
reads, so keep them in the source. They show in the rendered document unless
the audit-note extension removes them: render the auditor's copy without it
(the references show where each clause is met) and the engineers' copy with
it, from two configuration files.

## Architecture (C4)

The architecture is one [Structurizr](https://docs.structurizr.com/dsl)
workspace, `dhf/c4/workspace.dsl`: the C4 model (people, software systems,
containers, and components grouped by bounded context) and its views (the
system context and containers, and one component view per bounded context).
Each component names its code with a `code` property, a file or a directory:

```
group "graph" {
  projection = component "Projection" "The record into RDF" "Python" {
    properties {
      "code" "rdm/graph/"
    }
  }
}
```

`rdm c4 draw` exports the model to `dhf/c4/workspace.json` (what RDM reads)
and draws each view to `dhf/c4/views/<view>.svg`; every drawn file is
stamped with the workspace's hash. Commit them with the workspace. A
document shows its view as an ordinary image, so GitHub, the docs site and a
rendered PDF all show the same picture:

```markdown
![Components: graph](../../c4/views/C3_graph.svg)
```

The design gate fails when a drawn view is stale or missing
([the rules](../dhf/documents/design/architecture.md)). Drawing needs
`rdm-c4` (`RDM_C4`, or on the PATH), RDM's drawing program built from
[structurizrx](https://github.com/pomali/structurizrx) with
`cargo build --release --manifest-path providers/c4/Cargo.toml`; it does not
draw dynamic views yet, so a workspace with one also needs Java,
Structurizr's CLI (`RDM_STRUCTURIZR`, or `structurizr.sh` on the PATH) and
Graphviz, which draw everything when `rdm-c4` is absent. Checking needs only
the stamps.

## Template helpers

| Helper | What it does |
|---|---|
| `invert_dependencies` | group items by the things they depend on (e.g. tests per requirement) |
| `join_to` | join foreign keys to rows in another table by `id`; an id the table lacks is an error naming it, never a blank row |
| `md_indent` | indent an included snippet, optionally shifting its heading levels |
| `first_pass_output` | two-pass rendering: reference content computed later in the document (e.g. a table of contents over generated sections) |

## Collecting snippets from source

Keep fragments of documentation next to the code and pull them into documents:

```bash
rdm collect src/**/*.py > data/snippets.yml
```

Delimit snippets in any text file with `RDOC <name>` … `ENDRDOC`; each becomes
a named entry you can render with `{{ snippets.<name> }}`. A name used twice,
in one file or in two, is refused, naming where, and `rdm collect` exits 2.

## Translating test output

Convert machine test reports into a YAML data file a document can render:

```bash
rdm translate auto results.xml data/test_results.yml   # formats: auto, gtest, qttest, xunit
```

(For verification evidence, prefer Allure results and
[`rdm story verify`](design-inputs-and-tests.md): that path feeds the gates and the
traceability matrix.)

## Frontmatter conventions for controlled documents

Every controlled document declares its identity in YAML frontmatter:

```yaml
---
id: SOP-001        # stable identity
revision: 2        # bumped per approved change
title: "…"
---
```

Design documents additionally carry `kind: design`, `context` and
`design_inputs` (each with the needs it `traces_to`); see
[Design inputs and tests](design-inputs-and-tests.md).

## PDF and Word

A project from `rdm init` builds its documents with `make`:
`make` renders `release/*.md`, `make pdfs` the PDFs (Pandoc to Typst, styled
by `template.typ`) and `make docs` Word files. In CI, the
[PDF action](ci.md) runs `make pdfs` in the RDM image.

Pandoc's table model has no column or row spans and no vertical rules; raw
Typst in the Markdown is the way out when a table needs them.

The PDF's look is `template.typ`'s: the palette at its top (`maroon` for
headings and the cover, `charcoal`, `ivory`, `nexus`), `body-font` and
`mono-font`, and the cover. To brand it, change those; to add a logo, place
the image in the project and add `image("logo.svg", width: 3cm)` to the cover
block. The cover and header read the document's frontmatter: `id`,
`revision`, `title`, `date` (the build date when absent) and `status`
("Draft" when absent). The fonts must be installed where the PDF is built:
Nunito Sans and JetBrains Mono, with Noto Sans and DejaVu Sans Mono standing
in; the Docker setup has them.

For Word, give Pandoc your company template as a
[reference document](https://pandoc.org/MANUAL.html#option--reference-doc):
start from Pandoc's own, `pandoc -o reference.docx --print-default-data-file
reference.docx`, style it, and add `reference-doc: reference.docx` to
`pandoc_docx.yml`. The output takes its styles, header and footer, and `make`
rebuilds the Word files when it changes. Pandoc writes each frontmatter key as a Word document property,
so the header can show the document's id, revision and title: in the
reference document choose Insert → Field, pick `DocProperty`, and then the
property (Pandoc's are lower case, at the bottom of the list).

![Insert a field](../images/insert-form.png)
![Choose DocProperty](../images/select-docproperty-field.png)
![Choose the property](../images/select-property.png)
