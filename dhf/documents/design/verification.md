---
id: SDS-VER-001
kind: design
context: verification
design_inputs:
  - id: DI-4
    text: "RDM shall reconcile against Allure tags and render a traceability matrix from executed results."
    traces_to: [UN-004]
  - id: DI-18
    text: "RDM shall report the traceability slice for a given user need or design input (its design inputs / owner+realisers, verifying tests, and status)."
    traces_to: [UN-004]
  - id: DI-30
    text: "RDM shall produce a release evidence bundle from the record: the verification data, the rendered traceability matrix, the executed Allure results with every attachment and container they reference, and a manifest listing the bundle's files, written to an output directory for retention."
    traces_to: [UN-012]
  - id: DI-34
    text: "RDM shall provide a mutation probe for reviewers that runs a test once unmutated — reporting an error, never a result, when it does not pass — then applies a one-line source mutation, runs the test again, and reports killed or survived, counting only a genuine test failure as a kill; the probe never gates a release."
    traces_to: [UN-013]
  - id: DI-47
    text: "The mutation probe shall always restore the file it mutated: it journals the original beside the file so an interrupted probe is recovered on the next probe of that file, restores on a termination signal, and invalidates the bytecode cache on every write."
    traces_to: [UN-013]
  - id: DI-57
    text: "RDM shall provide a pytest plugin that, for each test tagged with a design input's story, labels the run from the record at test time — the input's user needs as Allure epics, its bounded context as the feature, links to the Markdown documents that declare it at the tested commit (its design document, the V&V plan for its user needs, and the risk document of each risk it controls), critical severity when it controls a risk — and attaches the input's text."
    traces_to: [UN-004, UN-010]
---

# Verification — Software Design

## Design Inputs

This context owns:

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
- **DI-34 (mutation probe, reviewer tool)** — `rdm story mutation-probe
  --file F --find A --replace B --test T` breaks one line on purpose, runs one
  test, and reports KILLED (the test caught it) or SURVIVED (it did not). It is
  how a pull-request reviewer turns "this test would catch a broken X" from a
  claim into an executed check. Only a genuine test failure is a kill; a run
  that errors or collects nothing is an error, so a typo'd selector cannot
  manufacture evidence. The file is always restored, defended in depth: the
  original is journaled to a sidecar first (recovered on the next probe of the
  file, even after SIGKILL), SIGTERM restores in-process, and every write
  advances the mtime to a fresh whole second so CPython never runs stale
  bytecode for a same-size mutant. It records nothing and gates nothing — the
  reviewer's judgment, on the pull request, is the record. Restored from the
  retired DI-21 without its verdict coupling. Refines UN-013.

- **DI-47 (the probe always restores)** — split from DI-34 (Design Review
  12) so the restore guarantees have their own test: the original is
  journaled beside the file, an interrupted probe is recovered on the next
  probe of that file, a termination signal restores, and every write
  invalidates the bytecode cache. Refines UN-013.

- **DI-57 (the Allure hierarchy, from the record)** — Allure organises
  results as epic → feature → story; RDM's record is user need → bounded
  context → design input. A test carries one hand-written tag,
  `@allure.story("DI-n")`; `rdm.pytest_plugin` (enabled with
  `-p rdm.pytest_plugin`, or its hook imported in the acceptance conftest;
  record at `--rdm-dhf`, default `dhf`) adds the rest at run time with Allure's dynamic API — epics,
  feature, links to the Markdown that declares it at the tested commit (the
  design document, the V&V plan for its user needs, the risk document of each
  risk it controls), critical severity for an input that controls a risk, and the requirement text as an
  attachment — so the labels cannot drift from the record. Refines UN-004
  and UN-010.

## Design Outputs

Turns executed test results into verification status and a traceable matrix.

- `rdm/record/allure.py` `reconcile()` — map Allure story/feature tags to design
  inputs; classify each as verified / failed / untested; flag orphan tags.
- `rdm/record/verify.py` + `rdm story verify` — write a `verification.yml` the
  DHF renders into a traceability matrix (design inputs grouped under the user
  need they trace to; generated, not hand-maintained).
- `rdm/gates/mutation.py` + `rdm story mutation-probe` — DI-34.
- `build_trace` + `rdm story trace <id>` — the read-only audit query: forward
  (user need → design inputs) and backward (design input → need, owner,
  realisers, verifying tests, status).

Contributes to **UN-003** (the release gate consumes this output) and **UN-004**.
Acceptance criteria are verified by `@allure.story("DI-4" / "DI-18")` tests.
