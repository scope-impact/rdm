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

Contributes to **UN-001** (compile the DHF from the system of record).
Acceptance criteria verified by `@allure.story("UN-001")` tests (and RDM's
existing render tests).
