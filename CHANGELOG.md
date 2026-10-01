# Changelog

## Unreleased

### Added — Allure labels from the record
- `rdm.pytest_plugin` labels each acceptance run with Allure's API: epic (user
  need), feature (bounded context), links to the Markdown documents that
  declare it (design document, V&V plan, risk document) at the commit, severity critical for a risk control, and the requirement text as an
  attachment (DI-57).

### Added — evidence tied to its version and its test
- `rdm.pytest_plugin` labels each tagged run with the commit under test and a
  dirty working tree (DI-59); the graph links run → commit and the record →
  the commit it was built at, and warns on an unversioned or stale run
  (DI-60).
- A test is a function (`rdm:Test`, defined in its file), and a run links to
  the test it ran; warnings for a test that never ran and a run whose test
  does not claim its design input (DI-61).
- Every controlled document, not only design documents, carries its latest
  commit and its landing (DI-35, DI-51).

### Removed — noise from the graph
- Allure labels as nodes (565 in RDM's own graph), links (`rdfs:seeAlso`),
  test cases (`rdm:runOf`) and container fixtures (DI-55, retired) are no
  longer projected: they repeated the record or said nothing about design
  controls. `story` and `output` labels still become `rdm:exercises` and
  `rdm:exercisesOutput`; the raw results stay in the evidence bundle (DI-54).
- The traceability matrix template is not projected, and its
  `prov:wasDerivedFrom` edges are gone (DI-58).

### Changed — a context's user needs are derived
- `satisfies` is gone from design documents: the needs a context serves follow
  from its design inputs' `traces_to` (owned or realised). The design gate's
  coverage warning reads the inputs, `rdm story new-input` no longer edits a
  context list, and the graph has no `rdm:satisfies` edge. A legacy key is
  ignored (DI-1, DI-22).

### Removed — `rdm:reviewedIn`
- Every design document was linked to the one design review because the gate
  requires a review to exist, not because the review covered it (DI-52).

### Added — no island documents
- The architecture declares the bounded contexts and their parts
  (`contexts:`), a document names the documents it relies on
  (`references:`); a context the architecture omits warns, a dangling reference fails
  `rdm graph validate` (DI-58).

### Fixed — Graph Explorer
- `rdm graph explorer-file --exclude TestRun` left a run's details (steps,
  attachments, labels, fixtures, test cases) as about a thousand islands; they
  now go with the excluded runs (DI-39).

### Changed — only the story names a design input
- Tag discovery reads `@allure.story` (Python), `allure.story` (JS/TS) and
  `@Story` (Java) only; a feature carries the context, not a design input
  (DI-31, DI-40).

### Added — Allure results as RDF
- `rdm/graph/allure.py`: each result's uuid, full name, times, status
  message and trace, and parameters (DI-54); output labels linking runs to
  source files, listed by `trace` (DI-56).

### Added — test evidence
- `rdm story evidence-bundle` keeps the executed Allure results, with every
  attachment and container they reference, in `allure-results/` (DI-30).
- The graph carries each test run's steps and attachments, and `trace` lists
  them with each run (DI-53).

### Fixed — security
- `rdm graph serve` ran Oxigraph read-write with CORS open: a cross-origin
  `CLEAR ALL` emptied the store. It now serves read-only (DI-36).
- The agent server's `query` refuses `SERVICE`, which made HTTP requests, and
  `trace` takes only id-shaped input (DI-42). Recorded as RISK-TOOL-006/007.

### Added — graph
- Who landed each design document's latest change on the default branch
  (`rdm:landedIn`, `rdm:landedBy`), with a warning while it has not (DI-51).
- User needs, risks and design documents link to the documents that declare,
  evaluate and review them (DI-52).

### Changed
- DI-34, DI-37, DI-38 and DI-44 are split into DI-47..50, one clause group and
  one test each (Design Review 12).

### Removed — DuckDB and the planning tooling
- `rdm story audit`, `sync`, `backlog-validate`, `check-ids`, `validate`;
  `rdm pm sync`; `rdm pull`; the `duckdb` query in templates; the
  `story-audit`, `analytics`, `github` and `plan` extras. The record and its
  RDF graph are the only data model (Design Review 11). DI-6, DI-13, DI-14,
  DI-23 and DI-32 retired.
- The gates (`design_gate`, `new_input`, `mutation`) move to `rdm/gates/` and
  are part of the core install.

### Added
- A user-need or design-input id declared more than once fails the design
  gate, naming every declaring document; the graph counts declarations and
  its shapes report a repeat (DI-46, UN-007).

### Changed — RDM as four parts
- README, docs home and navigation describe RDM as Record, Gates, Graph and
  Documents, with a plain "what it does not do" list; the intended use is in
  the V&V plan (Design Review 6).

### Added — read-only agent interface
- `rdm graph mcp`: an MCP stdio server with `schema`, `query`, `trace` and
  `validate`, each answering from a fresh projection of the record; no write
  tool, SPARQL Update refused, rows capped (UN-015, DI-41, DI-42).
  `.mcp.json` registers it for this repository.

### Added — risk register
- Risks as frontmatter in `kind: risk` documents: safety or security (with a
  STRIDE category), hazard → situation → harm, severity, probability,
  controls (design inputs), residual, acceptance, `status: proposed |
  approved` (UN-016, DI-43).
- No default matrix: risks are evaluated only against a declared
  `risk_policy` with per-level acceptability (`acceptable`, `justify`,
  `unacceptable`).
- The release gate blocks missing criteria, a broken chain, an undefined or
  mis-scored risk, an undeclared or unverified control, and an unacceptable
  or unaccepted residual; proposed ratings warn (DI-44).
- Risks in the graph (`risks` named graph, `rdm:controlledBy`, residual
  decision), risk shapes in agreement with the gate, risk ids in `trace`
  (DI-45). Design Reviews 9 and 10.

### Fixed
- Python test tags are read from decorators and `pytestmark` only, never
  from strings or comments (DI-40); every acceptance command passes
  `--clean-alluredir`, so repeated runs no longer double the results.
- `rdm story mutation-probe` runs the test once unmutated and refuses a test
  that does not pass — an already-failing test was reported KILLED (DI-34).
- `rdm story new-input` fills an empty `design_inputs: []` in place instead of
  writing a second key, and wraps its stub test's docstring (DI-22).
- User needs carry their text in the graph (DI-35).

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
- `rdm graph explorer-file`: the whole record as an AWS Graph Explorer graph
  file, so the full traceability graph opens in one step (DI-39).

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
