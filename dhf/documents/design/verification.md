---
id: SDS-VER-001
kind: design
context: verification
satisfies: [UN-003, UN-004]
design_inputs:
  - id: DI-4
    text: "RDM shall reconcile against Allure tags and render a traceability matrix from executed results."
    traces_to: [UN-004]
  - id: DI-18
    text: "RDM shall report the traceability slice for a given user need or design input (its design inputs / owner+realisers, verifying tests, and status)."
    traces_to: [UN-004]
---

# Verification — Software Design

## Design Inputs

This context owns, both refining UN-004:

- **DI-4 (traceability)** — reconcile against Allure tags and render a
  traceability matrix from executed results, not hand-maintained tables.
- **DI-18 (trace query)** — report the traceability slice for a given user need
  (→ its design inputs) or design input (→ its need(s), owner/realisers,
  verifying tests, and status), via `rdm story trace`.

## Design Outputs

Turns executed test results into verification status and a traceable matrix.

- `rdm/record/allure.py` `reconcile()` — map Allure story/feature tags to design
  inputs; classify each as verified / failed / untested; flag orphan tags.
- `rdm/record/verify.py` + `rdm story verify` — write a `verification.yml` the
  DHF renders into a traceability matrix (design inputs grouped under the user
  need they trace to; generated, not hand-maintained).
- `build_trace` + `rdm story trace <id>` — the read-only audit query: forward
  (user need → design inputs) and backward (design input → need, owner,
  realisers, verifying tests, status, faithfulness).

Contributes to **UN-003** (the release gate consumes this output) and **UN-004**.
Acceptance criteria are verified by `@allure.story("DI-4" / "DI-18")` tests.
