---
id: SDS-DASH-001
kind: design
context: dashboard
satisfies: [UN-001, UN-002]
design_inputs:
  - id: DI-3
    text: "VitalView shall let an authenticated clinician view a patient's current and recent vital signs."
    traces_to: [UN-002]
realises: [DI-1, DI-2]
---

# Dashboard — Software Design

## Design Inputs

This context owns **DI-3** (view vitals, refining UN-002). It also **realises**
**DI-1** and **DI-2** (owned by the `alerting` context) by presenting alerts
prominently and providing the acknowledge action.

## Design Outputs

The clinician-facing web UI: a patient list, a per-patient vitals view
(numerics + waveforms), and the alert banner with an acknowledge control.

This is the surface an **AI persona** drives (Playwright) for formative usability
evidence, and where the human summative usability study is run. Contributes to
**UN-001** and **UN-002**. Acceptance criteria are verified by tests tagged
`@allure.story("DI-1")` / `@allure.story("DI-2")` / `@allure.story("DI-3")`.
