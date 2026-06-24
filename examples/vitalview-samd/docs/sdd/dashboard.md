---
id: SDS-DASH-001
context: dashboard
satisfies: [UN-001, UN-002]
---

# Dashboard — Software Design

The clinician-facing web UI: a patient list, a per-patient vitals view
(numerics + waveforms), and the alert banner with an acknowledge control.

Contributes to:

- **UN-001** — presents alerts prominently and provides the acknowledge action.
- **UN-002** — lets a signed-in clinician find a patient and read current/recent
  vitals.

This is the surface an **AI persona** drives (Playwright) for formative usability
evidence, and where the human summative usability study is run. Acceptance
criteria verified by `@allure.story("UN-001")` / `@allure.story("UN-002")`.
