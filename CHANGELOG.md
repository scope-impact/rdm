# Changelog

## 1.2.0

Record-first design controls and an agentic faithfulness pipeline — RDM now
compiles and gates a Design History File from the system of record (per-context
design documents + executed Allure results + git), and dogfoods this on its own
development (`dhf/`, `.github/workflows/design-controls.yml`).

### Added — `rdm story` design-controls commands
- `design-gate` — block the transition into implementation until the per-context
  design documents (`kind: design`) and the design review are present, complete,
  and approved (committed) in git; an edit re-opens the gate.
- `verify` — reconcile design inputs against executed Allure results into a
  render-ready `verification.yml` (the generated traceability matrix).
- `release-gate` — block release unless every design input is verified by a
  passing test, independently confirmed *faithful*, and every user need is
  addressed.
- `faithfulness` — reconcile design inputs against independent verdicts
  (faithful / partial / unfaithful / stale / unreviewed); only `faithful` passes.
- `verdict` — record an independent faithfulness verdict, hash-pinned to the
  current verifying-test source (replaces a bundled skill script).
- `mutation-probe` — apply a one-line source mutation, run a test, report
  killed/survived, always restore the file (executed proof a test catches a defect).
- `trace` — show the traceability slice for a user need or design input
  (forward + backward).

### Added — model
- Per-context design documents discovered by a `kind: design` frontmatter marker;
  each owns its `design_inputs` (verified by `@allure.story("DI-…")` tests, "live
  BDD"), `satisfies` user needs, and may `realises` a shared design input.
- The `record/` core (reconcile, sdd, allure, verify, persona, faithfulness) is
  dependency-light (no project-management extra).
- AI-persona formative usability ingest (`persona`) — never gates release.

### Changed
- `[plan]` extra; planning tooling (Backlog.md / GitHub) is fenced as non-record.
