---
id: SDS-ALERT-001
context: alerting
satisfies: [UN-001]
---

# Alerting — Software Design

Evaluates ingested vitals against clinician-configured thresholds, raises a
prioritised alert when a dangerous change is detected, and tracks acknowledgement.

Contributes to:

- **UN-001** — a clinician is promptly alerted to dangerous changes and can
  acknowledge the alert.

This context realises design inputs **DI-1** (alert latency) and **DI-2** (alert
acknowledgement), both refining UN-001; their acceptance criteria are verified by
tests tagged `@allure.story("DI-1")` / `@allure.story("DI-2")` (e.g. a threshold
breach raises an alert within the specified latency; an acknowledged alert is
recorded).
