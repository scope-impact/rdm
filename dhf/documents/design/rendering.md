---
id: SDS-REN-001
kind: design
context: rendering
satisfies: [UN-001]
design_inputs:
  - id: DI-7
    text: "RDM shall render a Markdown template against a supplied data context with Jinja2, so generated data populates the document."
    traces_to: [UN-001]
  - id: DI-8
    text: "RDM shall provide the invert_dependencies, join_to, and md_indent template filters used to build traceability tables."
    traces_to: [UN-001]
  - id: DI-9
    text: "RDM shall post-process rendered Markdown: auto-number sections, expand declared vocabulary, and exclude auditor-only notes from released output."
    traces_to: [UN-001]
realises: [DI-1, DI-4]   # also renders the record-ingest + traceability outputs
---

# Rendering — Software Design

## Design Inputs

This context owns the rendering requirements (the engine that compiles the DHF
from data + templates), all refining UN-001:

- **DI-7 (template rendering)** — render a Markdown template against a supplied
  data context with Jinja2, so a generated data file (e.g. `verification.yml`)
  populates the document.
- **DI-8 (traceability filters)** — provide `invert_dependencies`, `join_to`,
  and `md_indent`, the filters that build traceability tables.
- **DI-9 (markdown post-processing)** — auto-number sections, expand declared
  vocabulary/acronyms, and exclude auditor-only `[[…]]` notes from released
  output.

It also **realises** the rendering side of inputs owned elsewhere (via
`realises`): **DI-1** (record ingest, owned by `record`) and **DI-4**
(traceability, owned by `verification`).

## Design Outputs

Compiles the DHF from data and templates.

- `rdm/render.py` — two-pass Jinja2 engine; the `invert_dependencies`, `join_to`,
  `md_indent` filters; context keyed by data-file basename, so a generated
  `verification.yml` renders into a traceability matrix.
- `rdm/md_extensions/` — section numbering, vocabulary expansion, auditor-note
  exclusion.
- Output: Markdown → PDF/DOCX via Pandoc/Typst.

Contributes to **UN-001** (compile the DHF from the system of record). The owned
inputs are verified by `@allure.story("DI-7" / "DI-8" / "DI-9")` acceptance tests;
the realised inputs by their owners' tests plus RDM's existing render unit tests.
