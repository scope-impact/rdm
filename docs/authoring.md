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

## Template helpers

| Helper | What it does |
|---|---|
| `invert_dependencies` | group items by the things they depend on (e.g. tests per requirement) |
| `join_to` | join foreign keys to rows in another table by `id` |
| `md_indent` | indent an included snippet, optionally shifting its heading levels |
| `first_pass_output` | two-pass rendering: reference content computed later in the document (e.g. a table of contents over generated sections) |

DuckDB-backed queries are also available for templates that report over synced
planning data — see the [API reference](reference.md) for `rdm.render`.

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

Design documents additionally carry `kind: design`, `context`, `satisfies`,
and `design_inputs` — see [Design controls](design-controls.md).
