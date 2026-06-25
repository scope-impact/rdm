---
id: SDS-VER-001
context: verification
satisfies: [UN-003, UN-004]
---

# Verification — Software Design

Turns executed test results into verification status and a traceable matrix.

- `rdm/record/allure.py` `reconcile()` — map Allure story/feature tags to design
  inputs; classify each as verified / failed / untested; flag orphan tags.
- `rdm/record/verify.py` + `rdm story verify` — write a `verification.yml` the
  DHF renders into a traceability matrix (design inputs grouped under the user
  need they trace to; generated, not hand-maintained).

Contributes to **UN-003** (the release gate consumes this) and **UN-004**
(verification status traceable from executed results). This context realises
design input **DI-4** (reconcile against Allure tags and render the matrix from
executed results); its acceptance criteria are verified by `@allure.story("DI-4")`
tests.
