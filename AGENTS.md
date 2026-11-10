# Working on RDM

RDM audits a specification; it does not author one (ADR 0002). Its job is gap
analysis against compliance checklists and a traceability graph built from
Allure results. Writing the spec and regenerating code from it belong to the
factory, Löyly (`loyly/`).

- A behaviour change is covered by a **design input** (`DI-n`), declared in its
  owning context's `design_inputs` frontmatter under `dhf/documents/design/`,
  and verified by a test tagged `@allure.story("DI-n")` in `tests/acceptance/`.
  A test checks only what its input's text states.
- Run the tests and the gates as CI does (`CLAUDE.md`). A reviewer other than
  the author approves each pull request.
- Never hand-edit `dhf/documents/traceability_matrix.md` — it is generated
  from executed results.

Plans, tasks and issues are coordination, never the record: never cite them
as design, verification or approval evidence (`docs/about/plan-vs-record.md`).
