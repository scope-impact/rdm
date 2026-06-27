---
id: SDS-ALERT-001
kind: design
context: alerting
satisfies: [UN-001]
design_inputs:
  - id: DI-1
    text: "VitalView shall raise a prioritised alert within the specified latency when ingested vitals breach a clinician-configured threshold."
    traces_to: [UN-001]
  - id: DI-2
    text: "VitalView shall record clinician acknowledgement of an alert, with author and timestamp."
    traces_to: [UN-001]
---

# Alerting — Software Design

## Design Inputs

This context owns **DI-1** (alert latency) and **DI-2** (alert acknowledgement),
both refining UN-001.

## Design Outputs

Evaluates ingested vitals against clinician-configured thresholds, raises a
prioritised alert when a dangerous change is detected, and tracks acknowledgement.

Contributes to **UN-001** — a clinician is promptly alerted to dangerous changes
and can acknowledge the alert. Acceptance criteria are verified by tests tagged
`@allure.story("DI-1")` / `@allure.story("DI-2")` (e.g. a threshold breach raises
an alert within the specified latency; an acknowledged alert is recorded).
