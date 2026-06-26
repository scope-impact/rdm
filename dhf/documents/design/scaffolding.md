---
id: SDS-SCAF-001
kind: design
context: scaffolding
satisfies: [UN-008]
design_inputs:
  - id: DI-15
    text: "RDM shall scaffold a new documentation project from one command, laying down the document templates, build Makefile, and render config."
    traces_to: [UN-008]
---

# Scaffolding — Software Design

## Design Inputs

This context owns **DI-15 (project scaffolding)**, refining UN-008: `rdm init`
lays down a complete starting project — the document templates, the build
`Makefile`, and the render `config.yml` — so a regulatory author starts from a
working skeleton rather than a blank repo.

## Design Outputs

`rdm init` and `rdm/init.py`:

- `init(output_directory)` — copies the packaged `rdm/init_files/` tree into a
  new project directory (templates, `Makefile`, `config.yml`, Dockerfile, pandoc
  configs, `data/`, `images/`).
- `rdm/init_files/documents/` — the shipped templates, including the
  design-controls set (the `kind: design` document template, `design_review.md`,
  `traceability_matrix.md`) so new projects inherit the record-first model.

Acceptance criterion is verified by `@allure.story("DI-15")` — the scaffold lays
down the expected files. The heavier end-to-end check (the scaffold *builds* a
release and passes the gap checklists) is covered by `fresh_release_test.py`,
which runs `make` + `rdm gap` and needs Pandoc, so it stays in the main suite
rather than the design-controls job.
