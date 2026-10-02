---
id: SDS-REL-001
kind: design
context: release
# Implements part of inputs other contexts own.
realises: [DI-33, DI-44, DI-50]
design_inputs:
  - id: DI-3
    text: "RDM shall block release unless every declared design input is verified by a passing test."
    traces_to: [UN-003]
  - id: DI-4
    text: "RDM shall reconcile against Allure tags and render a traceability matrix from executed results."
    traces_to: [UN-004]
  - id: DI-18
    text: "RDM shall report the traceability slice for a given user need or design input (its design inputs / owner+realisers, verifying tests, and status)."
    traces_to: [UN-004]
  - id: DI-30
    text: "RDM shall produce a release evidence bundle from the record: the verification data, the rendered traceability matrix, the executed Allure results with every attachment and container they reference, and a manifest listing the bundle's files, written to an output directory for retention."
    traces_to: [UN-012]
  - id: DI-63
    text: "RDM shall provide its gates for reuse in another repository's CI: a reusable workflow that runs the repository's acceptance tests and then the design gate, verify, the release gate, graph validation and the evidence bundle, with RDM installed from the revision the caller pinned rather than a package index; a composite gates action that does the same for workflows of their own; and a composite action that renders the documents with the image of the same revision. The CI workflow rdm adopt lays down shall call the reusable workflow pinned to the installed RDM's version."
    traces_to: [UN-011, UN-003]
---

# Release — Software Design

## Purpose

`release` decides whether a release may go ahead and keeps the evidence it
went ahead on: the release gate over the record's verified status, its risks
and its user needs; the verification data and trace slice the gate and its
reviewers read; the evidence bundle a release retains; and the reusable gates
that run all of it in another repository's pipeline. Its language: a design
input is *verified* (a passing run of a tagged test, no failed one); the
*release gate* blocks a release; *release-grade evidence* is what a release
needs; the *traceability matrix* is generated, never edited; the *evidence
bundle* is what a release keeps. It reads the specification, test evidence
and risk; none of them reads it.

## Design Inputs

- **DI-3 (release gate)** — block release unless every declared design input
  is verified by a passing test. Whether the test proves its input is left to
  the independent review of the pull request; no verdict is recorded.
  Refines UN-003.
- **DI-4 (traceability)** — reconcile against Allure tags and render a
  traceability matrix from executed results, not hand-maintained tables.
  Owned here for the verification data the gate and the bundle read; reading
  the results (`test_evidence`) and rendering the template (`publishing`) are
  realised elsewhere. Refines UN-004.
- **DI-18 (trace query)** — report the traceability slice for a given user
  need (→ its design inputs) or design input (→ its need(s), owner/realisers,
  verifying tests, and status), via `rdm story trace`. Read-only. Refines
  UN-004.
- **DI-30 (release evidence bundle)** — `rdm story evidence-bundle` writes the
  release's retained evidence set to an output directory: the verification
  data, the rendered traceability matrix, and a manifest describing the
  bundle — the DHR-shaped set a team attaches to a release tag. Refines
  UN-012.
  Amended (Design Review 13): the bundle also keeps the executed Allure
  results themselves — every result, the attachments and containers they
  reference — so the evidence behind each verdict outlives CI. Integrity is
  the pipeline's: GitHub's artifact upload records the artifact's digest.
- **DI-63 (the gates, reusable from another repository)** — the CI `rdm
  adopt` used to lay down ran `pip install rdm`, which fetches an unrelated
  package of the same name, and every team copied steps that then drifted
  from RDM's own. Now RDM's CI is the reusable unit:
  `scope-impact/rdm/.github/workflows/gates.yml@<ref>` runs the caller's
  acceptance tests, then the design gate, `verify`, the release gate
  (optional while a record has no design input yet), `rdm graph validate`
  (with any checklists) and the evidence bundle, uploaded as an artifact;
  `scope-impact/rdm/actions/gates@<ref>` runs the same gates inside a
  workflow of one's own; `scope-impact/rdm@<ref>` renders the documents in
  the image published for that revision. RDM is installed from the pinned
  revision itself — never by name from a package index — so the gates that
  ran are exactly the ones pinned. The workflow `rdm adopt` lays down calls
  the reusable workflow at `v<installed RDM version>`; RDM's own CI calls
  it too, so every push to RDM exercises it. Refines UN-011 and UN-003.

## Design Outputs

- **Release gate** (`rdm/release/gate.py`, `rdm story release-gate`) — DI-3.
  It blocks when the design gate's pass/fail checks fail, when no design
  input is declared, when a design input failed or is untested in the given
  Allure results, when the risk register's release rules report a finding
  (`risk`'s DI-44 and DI-50), and when a user need is addressed by no design
  input. A user need with no approved validation record (`specification`'s
  DI-33) and an Allure tag matching no design input are warnings. It does not
  check the commit or worktree a run recorded; the verification report
  (`publishing`) and, as a warning, the graph's gate shapes do. The same
  module gives the design gate its warnings about executed results, which the
  composition root hands to the design gate's output.
- **Trace** (`build_trace`, `rdm story trace <id>`) — DI-18, in the same
  module: a user need's design inputs, or a design input's text, user needs,
  owner, realisers (from `realises`) and, with results, status and tests.
- **Verification data** (`rdm/release/verify.py`, `rdm story verify`) — DI-4:
  reconciles every declared design input against the Allure results into
  `verification.yml` (status, run counts, tests and outputs per design
  input, grouped by user need, with a summary and the orphan tags), the data
  the traceability matrix template renders.
- **Reusable workflow** (`.github/workflows/gates.yml`) — DI-63: checks out
  the caller and RDM at the required `rdm-ref` (a reusable workflow cannot
  see the ref it was called at, so the caller writes it twice), installs
  `rdm[graph,report]`, `pytest` and `allure-pytest` from that checkout, runs
  the caller's tests, then the Gates action, and uploads the Allure report
  and the verification record.
- **Gates action** (`actions/gates/`) — DI-63: the gate steps, written once:
  installs `rdm[graph,report]` from its own revision unless told not to,
  then the design gate, `verify`, the release gate, graph validation and the
  evidence bundle (uploaded), each switchable by an input; `verify` and the
  bundle run only when there are Allure results. Inputs reach the scripts
  as environment variables, never spliced into the script text.

This context realises parts of `specification`'s DI-33 and `risk`'s DI-44
and DI-50: the release gate reports and applies them. Parts of its own are
realised elsewhere: DI-4 by `test_evidence` and `publishing`, and DI-30 by
`publishing`, whose evidence bundle writes the retained release evidence. The rest of
DI-63 is in components of other contexts that do not declare it: the PDF
action (`action.yml`, `publishing`) and the adopted `design-controls.yml`
(`rdm/specification/adopt.py`, `specification`).

The evidence bundle (DI-30), the verification report (DI-64) and the matrix
rendering are in [publishing](publishing.md); the mutation probe and the pytest plugin in
[test evidence](test_evidence.md).

Verified by the tests tagged DI-3 and DI-4 in
`tests/acceptance/test_user_needs.py`, DI-18 in `test_trace.py`, DI-30 in
`test_verification.py` and DI-63 in `test_reusable_ci.py`.

## Components (C3)

![Components: release](../../c4/views/C3_release.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Release gate | The release decision; the design gate's results warnings; the trace slice | Python | `rdm/release/gate.py` |
| Verification data | Design inputs against results: `verification.yml` | Python | `rdm/release/verify.py` |
| Reusable workflow | Tests, then the gates, for any repository | GitHub Actions | `.github/workflows/gates.yml` |
| Gates action | The gates as steps | GitHub Actions | `actions/gates/` |

- The release gate runs the design gate's checks, reads the record and the
  validation records (`specification`), reconciles results with the Allure
  reader (`test_evidence`) and reports the risk findings of the risk register
  (`risk`).
- Verification data reconciles results with the Allure reader and reads the
  record with the record reader.
- The Reusable workflow runs the acceptance tests and then the gates with the
  Gates action, which runs the design gate, `verify`, the release gate, graph
  validation and the evidence bundle.

Open question: the release gate does not decide whether the runs are
release-grade evidence (the commit and worktree each run tested); the
verification report and the knowledge graph show it. Whether the gate
should is a question for a later review.

### Dynamic view

The order of a pipeline run matters: the acceptance tests write the Allure
results; the design gate runs; `verify` writes the verification data; the
release gate decides; graph validation runs; the evidence bundle is written
last, from the same results, and uploaded. The component view shows what
uses what, not this order. The view below draws it at the container level,
where the order is decided (a dynamic view's steps cannot cross from the
Reusable gates container into `rdm`'s components):

![Scenario: one run of the reusable gates](../../c4/views/D_release_pipeline.svg)

## Dependencies

Layer 4 of the dependency rule. Depends on `test_evidence` (the Allure
reader), `specification` (the record reader, the design gate, the validation
records), `risk` (the risk rules) and the shared kernel, all below it.
Depended on by `publishing` (the verification report and the evidence bundle
build on the verification data) and by the composition root, which hands the
design gate this context's results warnings. No import breaks the rule.
