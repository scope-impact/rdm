---
id: SDS-REN-001
kind: design
context: rendering
satisfies: [UN-001]
design_inputs: []        # owns none; contributes to inputs owned elsewhere
realises: [DI-1, DI-4]   # the rendering side of record-ingest + traceability
---

# Rendering — Software Design

## Design Inputs

This context owns no design inputs of its own. It **realises** the rendering side
of inputs owned by other contexts (declared via `realises`):

- **DI-1** (record ingest, owned by `record`) — renders the compiled DHF.
- **DI-4** (traceability, owned by `verification`) — renders the traceability
  matrix from the generated `verification.yml`.

## Design Outputs

Compiles the DHF from data and templates.

- `rdm/render.py` — two-pass Jinja2 engine; custom filters
  (`invert_dependencies`, `join_to`, `md_indent`); context keyed by data-file
  basename, so a generated `verification.yml` renders into a traceability
  matrix.
- `rdm/md_extensions/` — section numbering, vocabulary expansion, auditor-note
  exclusion.
- Output: Markdown → PDF/DOCX via Pandoc/Typst.

Contributes to **UN-001** (compile the DHF from the system of record). The
realised inputs are verified by `@allure.story("DI-1")` / `@allure.story("DI-4")`
tests together with RDM's existing render tests.
