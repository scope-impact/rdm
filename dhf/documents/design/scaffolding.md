---
id: SDS-SCAF-001
kind: design
context: scaffolding
satisfies: [UN-008]
design_inputs:
  - id: DI-15
    text: "RDM shall scaffold a new documentation project from one command, laying down the document templates, build Makefile, and render config."
    traces_to: [UN-008]
  - id: DI-22
    text: "RDM shall scaffold an agent operating procedure (AGENTS.md) that documents the record-first workflow: design approved (committed) before implementation, each design input verified by an @allure.story-tagged acceptance test, the design/release gates run via rdm story, and an independent faithfulness review of every verifying test."
    traces_to: [UN-008]
---

# Scaffolding — Software Design

## Design Inputs

This context owns **DI-15 (project scaffolding)**, refining UN-008: `rdm init`
lays down a complete starting project — the document templates, the build
`Makefile`, and the render `config.yml` — so a regulatory author starts from a
working skeleton rather than a blank repo.

It also owns **DI-22 (agent operating procedure)**, refining UN-008: the
scaffold includes an `AGENTS.md` documenting the record-first workflow for AI
coding agents (and humans) working in the new project — design approved
(committed) before implementation, each design input verified by an
`@allure.story("DI-…")`-tagged acceptance test, the gates run via `rdm story`,
and an independent faithfulness review of every verifying test. A compliant
project is only "compliant from one command" (UN-008) if the workflow that keeps
it compliant ships with it.

## Design Outputs

`rdm init` and `rdm/init.py`:

- `init(output_directory)` — copies the packaged `rdm/init_files/` tree into a
  new project directory (templates, `Makefile`, `config.yml`, Dockerfile, pandoc
  configs, `data/`, `images/`).
- `rdm/init_files/documents/` — the shipped templates, including the
  design-controls set (the `kind: design` document template, `design_review.md`,
  `traceability_matrix.md`) so new projects inherit the record-first model.
- `rdm/init_files/AGENTS.md` — the agent operating procedure laid down with the
  scaffold (DI-22): the record-first loop (design → committed approval → tagged
  acceptance test → implementation → gates → independent faithfulness review),
  the `rdm story` gate commands, and the hard rules (generated artifacts are
  never hand-edited; verdicts are recorded only by a reviewer independent of the
  test author).

Acceptance criteria are verified by `@allure.story("DI-15")` — the scaffold lays
down the expected files — and `@allure.story("DI-22")` — the scaffold lays down
`AGENTS.md` and it documents the workflow and gates. The heavier end-to-end
check (the scaffold *builds* a release and passes the gap checklists) is covered
by `fresh_release_test.py`, which runs `make` + `rdm gap` and needs Pandoc, so
it stays in the main suite rather than the design-controls job.
