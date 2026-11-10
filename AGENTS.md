# Working on RDM

RDM audits a specification; it does not author one (ADR 0002). Its job is gap
analysis against compliance checklists and a traceability graph built from
Allure results, over a spec in Gherkin.

- A behaviour change is covered by a **requirement**: a scenario of RDM's own
  spec, `spec/<context>/<need>.feature`, tagged `@item-NN-SSS`, verified by a
  test tagged `@allure.tag("item-NN-SSS")` in `tests/acceptance/`. A test
  checks only what its scenario states.
- Run the tests and the gates as CI does (`CLAUDE.md`). A reviewer other than
  the author approves each pull request.
- Never hand-edit `dhf/documents/traceability_matrix.md` — it is generated
  from executed results.

Plans, tasks and issues are coordination, never the record: never cite them
as design, verification or approval evidence (`docs/about/plan-vs-record.md`).
