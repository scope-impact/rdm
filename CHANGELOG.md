# Changelog

## Unreleased

### Added — the design record as a linked-data graph
- `rdm graph build | query | serve` (optional extra `graph`): projects the
  record into RDF named graphs (record, tests, executions, git, ontology),
  stores it in an embedded Oxigraph database, answers SPARQL, and serves a
  SPARQL 1.1 endpoint for AWS Graph Explorer. DI-35, DI-36, user need UN-014.
- Checklists and `[[KEY]]` reference tags in the graph, as SKOS data: a new
  standard or checklist is a `.txt` or RDF file, never a code change; matched
  with `rdm gap`'s own reader and matcher (DI-37).
- `rdm graph validate`: the gate rules as SHACL shapes (plus `--shapes` for
  your own), held by test to agreement with the release gate (DI-38).

### Changed — `rdm story mutation-probe` is a standalone reviewer tool
- Same command and restore guarantees, now recorded as DI-34 (user need
  UN-013): a reviewer proves a test catches a specific defect. It records no
  verdict and gates nothing.

### Removed — the faithfulness gate
- `rdm story faithfulness` and `rdm story verdict`, faithfulness verdicts (`dhf/faithfulness/*.json`), probe replay, verdict hash
  scope, the `test-faithfulness` skill and `contrib/mutmut_by_design_input.py`.
  Retired design inputs DI-19, DI-20, DI-21, DI-27, DI-28 and user need UN-009
  (ids are not reused).
- Why: the verdicts were RDM's own construct, not a §820.30 or IEC 62304
  requirement, and their hash pins re-opened reviews on edits that changed no
  requirement or product behavior. Independent verification is now the
  human-reviewed pull request, with git as the controlled record (Design
  Review 4).

### Changed
- CI runs design-gate → acceptance tests (Allure) → verify → release-gate.
- `release-gate` requires the design approved, every design input verified by
  a passing tagged test, and every user need addressed — no verdicts.
- `rdm story evidence-bundle` no longer includes verdicts.

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
