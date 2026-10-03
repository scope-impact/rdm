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

- `config.yml` configures rendering (e.g. `md_extensions` for section
  numbering / vocabulary expansion).
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
and draws each view with Graphviz to `dhf/c4/views/<view>.svg`; every drawn
file is stamped with the workspace's hash. Commit them with the workspace. A
document shows its view as an ordinary image, so GitHub, the docs site and a
rendered PDF all show the same picture:

```markdown
![Components: graph](../../c4/views/C3_graph.svg)
```

The design gate fails when a drawn view is stale or missing
([the rules](dhf/documents/design/architecture.md)). Drawing needs Java,
Structurizr's CLI (`RDM_STRUCTURIZR`, or `structurizr.sh` on the PATH) and
Graphviz; checking needs only the stamps.

## Template helpers

| Helper | What it does |
|---|---|
| `invert_dependencies` | group items by the things they depend on (e.g. tests per requirement) |
| `join_to` | join foreign keys to rows in another table by `id` |
| `md_indent` | indent an included snippet, optionally shifting its heading levels |
| `first_pass_output` | two-pass rendering: reference content computed later in the document (e.g. a table of contents over generated sections) |

## Collecting snippets from source

Keep fragments of documentation next to the code and pull them into documents:

```bash
rdm collect src/**/*.py > data/snippets.yml
```

Delimit snippets in any text file with `RDOC <name>` … `ENDRDOC`; each becomes
a named entry you can render with `{{ snippets.<name> }}`.

## Translating test output

Convert machine test reports into a YAML data file a document can render:

```bash
rdm translate auto results.xml data/test_results.yml   # formats: auto, gtest, qttest, xunit
```

(For verification evidence, prefer Allure results and
[`rdm story verify`](design-controls.md): that path feeds the gates and the
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
[Design inputs and tests](design-controls.md).

## PDF and Word

A project from `rdm init` builds its documents with `make`:
`make` renders `release/*.md`, `make pdfs` the PDFs (Pandoc to Typst, styled
by `template.typ`) and `make docs` Word files. In CI, the
[PDF action](reusable-ci.md) runs `make pdfs` in the RDM image.

Pandoc's table model has no column or row spans and no vertical rules; raw
Typst in the Markdown is the way out when a table needs them.

For Word, give Pandoc your company template as a
[reference document](https://pandoc.org/MANUAL.html#option--reference-doc)
(`reference-doc:` in `pandoc_docx.yml`): the output takes its styles, header
and footer. Pandoc writes each frontmatter key as a Word document property,
so the header can show the document's id, revision and title: in the
reference document choose Insert → Field, pick `DocProperty`, and then the
property (Pandoc's are lower case, at the bottom of the list).

![Insert a field](./images/insert-form.png)
![Choose DocProperty](./images/select-docproperty-field.png)
![Choose the property](./images/select-property.png)
