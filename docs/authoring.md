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

## Mermaid diagrams

A ` ```mermaid ` block, such as a C4 view, becomes an image in the rendered
document when `config.yml` lists `rdm.md_extensions.MermaidExtension` (as
`rdm init`'s does). The image is drawn by Mermaid's own renderer, so the PDF
shows what GitHub and the docs site show. A diagram that cannot be drawn fails
the render with the document's name; it never ships as source.

The renderer is Mermaid's official image, pinned by version and digest, run
beside the RDM image rather than inside it, so the RDM image carries no
browser. `render-pdfs.sh` (laid down by `rdm init`, and what RDM's PDF action
runs) does the three steps on shared files:

```bash
dhf/render-pdfs.sh dhf pdfs   # from the repository root; needs Docker
```

1. render the Markdown with `RDM_MERMAID_COLLECT=1`, which writes each undrawn
   diagram to `dhf/tmp/mermaid/<hash>.mmd`;
2. draw each one with Mermaid's image;
3. render again: every diagram is now its image, named by a hash of its text.

With `mmdc` installed (`npm install -g @mermaid-js/mermaid-cli`), `rdm render`
draws a diagram itself, in one step.

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

(For design-controls verification evidence, prefer Allure results and
[`rdm story verify`](design-controls.md) — that path feeds the gates and the
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
`design_inputs` (each with the needs it `traces_to`) — see [Design controls](design-controls.md).
