---
id: SDS-VER-001
context: verification
satisfies: [UN-003, UN-004]
---

# Verification — Software Design

Turns executed test results into verification status and a traceable matrix.

- `rdm/record/allure.py` `reconcile()` — map Allure story/feature tags to user
  needs; classify each as verified / failed / untested; flag orphan tags.
- `rdm/record/verify.py` + `rdm story verify` — write a `verification.yml` the
  DHF renders into a traceability matrix (generated, not hand-maintained).

Contributes to **UN-003** (the release gate consumes this) and **UN-004**
(verification status traceable from executed results). Acceptance criteria
verified by `@allure.story("UN-003")` / `@allure.story("UN-004")` tests.
