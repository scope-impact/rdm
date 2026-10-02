---
id: SDS-REL-001
kind: design
context: release
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

> **Interim (Design Review 30).** This context was formed from `gating`, `verification`, `scaffolding`. Its
> design inputs moved here unchanged; the prose below is carried over verbatim
> from those documents, by section, until it is rewritten for this context.

## Design Inputs

Decides whether a release may go ahead: the release gate over verified status, risk and validation, the verification data and trace slice it reads, the evidence it retains, and the reusable CI that runs it.

- **DI-3 (release gate)** — block release unless every declared design input is
  verified by a passing test. Refines UN-003.
- **DI-4 (traceability)** — reconcile against Allure tags and render a
  traceability matrix from executed results, not hand-maintained tables.
  Refines UN-004.
- **DI-18 (trace query)** — report the traceability slice for a given user need
  (→ its design inputs) or design input (→ its need(s), owner/realisers,
  verifying tests, and status), via `rdm story trace`. Refines UN-004.
- **DI-30 (release evidence bundle)** — `rdm story evidence-bundle` writes the
  release's retained evidence set to an output directory: the verification
  data, the rendered traceability matrix, and a manifest describing the bundle — the DHR-shaped artifact set a team
  attaches to a release tag. Refines UN-012.
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

The rest of `gating`'s design is carried over in [specification](specification.md).

The rest of `scaffolding`'s design is carried over in [specification](specification.md).

## Design Inputs (context notes) — from `verification`

This context owns:

## Design Outputs — from `verification`

Turns executed test results into verification status and a traceable matrix.

- `rdm/record/allure.py` `reconcile()` — map Allure story/feature tags to design
  inputs; classify each as verified / failed / untested; flag orphan tags.
- `rdm/record/verify.py` + `rdm story verify` — write a `verification.yml` the
  DHF renders into a traceability matrix (design inputs grouped under the user
  need they trace to; generated, not hand-maintained).
- `rdm/gates/mutation.py` + `rdm story mutation-probe` — DI-34.
- `rdm/record/report.py` + `rdm story evidence-report` — DI-64: `build_report()`
  gathers the report data from the record and the results;
  `rdm/record/verification_report.typ` lays it out; `render_pdf()` compiles it
  with the `typst` package (extra `report`) or a `typst` executable.
- `rdm/pytest_plugin.py` — DI-65: writes `executor.json` and
  `environment.properties` into the Allure results once per session. `evidence_bundle()` writes
  `verification_report.pdf` next to the matrix when the extra is installed,
  and records in the manifest why not otherwise.
- `build_trace` + `rdm story trace <id>` — the read-only audit query: forward
  (user need → design inputs) and backward (design input → need, owner,
  realisers, verifying tests, status).

Contributes to **UN-003** (the release gate consumes this output) and **UN-004**.
Acceptance criteria are verified by `@allure.story("DI-4" / "DI-18")` tests; DI-64 by
`tests/acceptance/test_verification_report.py`; DI-65 by `tests/acceptance/test_run_environment.py`.

## Components (C3)

The components of the `release` context, drawn from the architecture
workspace (`rdm c4 draw`).

![Components: release](../../c4/views/C3_release.svg)
