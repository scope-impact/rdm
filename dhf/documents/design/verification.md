---
id: SDS-VER-001
kind: design
context: verification
satisfies: [UN-003, UN-004]
design_inputs:
  - id: DI-4
    text: "RDM shall reconcile against Allure tags and render a traceability matrix from executed results."
    traces_to: [UN-004]
---

# Verification — Software Design

## Design Inputs

This context owns **DI-4 (traceability)** — reconcile against Allure tags and
render a traceability matrix from executed results, not hand-maintained tables.
Refines UN-004.

## Design Outputs

Turns executed test results into verification status and a traceable matrix.

- `rdm/record/allure.py` `reconcile()` — map Allure story/feature tags to design
  inputs; classify each as verified / failed / untested; flag orphan tags.
- `rdm/record/verify.py` + `rdm story verify` — write a `verification.yml` the
  DHF renders into a traceability matrix (design inputs grouped under the user
  need they trace to; generated, not hand-maintained).

Contributes to **UN-003** (the release gate consumes this output) and **UN-004**.
Acceptance criteria are verified by `@allure.story("DI-4")` tests.
