---
id: SDS-SCAF-001
kind: design
context: scaffolding
satisfies: [UN-008, UN-010]
design_inputs:
  - id: DI-15
    text: "RDM shall scaffold a new documentation project from one command, laying down the document templates, build Makefile, and render config."
    traces_to: [UN-008]
  - id: DI-22
    text: "RDM shall scaffold a new design input: allocate the next unused DI id, insert the entry into the chosen context's design_inputs frontmatter, emit a stub acceptance test tagged with the new id that fails until implemented, and print the remaining traceability checklist, rejecting an unknown context or user need."
    traces_to: [UN-010]
---

# Scaffolding — Software Design

## Design Inputs

This context owns:

- **DI-15 (project scaffolding)**, refining UN-008: `rdm init` lays down a
  complete starting project — the document templates, the build `Makefile`, and
  the render `config.yml` — so a regulatory author starts from a working
  skeleton rather than a blank repo.
- **DI-22 (design-input scaffolding)**, refining UN-010: `rdm story new-input`
  guides a contributor (human or agent) through authoring a *traced* design
  input rather than a loose one — it allocates the next unused DI id across the
  whole DHF, inserts the `{id, text, traces_to}` entry into the chosen context's
  `design_inputs` frontmatter, emits a stub acceptance test tagged
  `@allure.story("DI-n")` that **fails until implemented** (so the release gate
  stays honestly red), and prints the remaining traceability checklist (design
  prose, commit-approval, implementation, real assertions, faithfulness verdict,
  gates, matrix). An unknown context or user need is rejected — a design input
  can never be scaffolded outside the record.

## Design Outputs

For **DI-22** — `rdm story new-input` and `rdm/story_audit/new_input.py`:

- reuses the record ingest layer (`rdm/record/sdd.py`: `find_design_docs`,
  `design_input_ids`, `registry_user_needs`, `context_of`) so the scaffolder and
  the gates share one view of the DHF;
- inserts the frontmatter entry by targeted line edit (never a YAML re-dump), so
  hand-authored formatting and comments in the design doc survive;
- `--list` prints the discovery inventory (contexts, existing DI ids, next free
  id, user needs) read-only.

For **DI-15** — `rdm init` and `rdm/init.py`:

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
