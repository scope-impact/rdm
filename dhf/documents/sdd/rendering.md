---
id: SDS-REN-001
context: rendering
satisfies: [UN-001]
---

# Rendering — Software Design

Compiles the DHF from data and templates.

- `rdm/render.py` — two-pass Jinja2 engine; custom filters
  (`invert_dependencies`, `join_to`, `md_indent`); context keyed by data-file
  basename, so a generated `verification.yml` renders into a traceability
  matrix.
- `rdm/md_extensions/` — section numbering, vocabulary expansion, auditor-note
  exclusion.
- Output: Markdown → PDF/DOCX via Pandoc/Typst.

Contributes to **UN-001** (compile the DHF from the system of record). This
context realises the rendering side of design inputs **DI-1** (compile) and
**DI-4** (render the traceability matrix from executed results); their acceptance
criteria are verified by `@allure.story("DI-1")` / `@allure.story("DI-4")` tests
together with RDM's existing render tests.
