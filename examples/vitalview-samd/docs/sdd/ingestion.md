---
id: SDS-INGEST-001
context: ingestion
satisfies: [UN-001, UN-002]
---

# Ingestion — Software Design

Receives streamed vital signs from monitor gateways, validates and timestamps
them, and stores current and recent values per patient.

Contributes to:

- **UN-001** — alerting and the dashboard need fresh, ordered vitals to act on.
- **UN-002** — the dashboard reads current and recent vitals from here.

Acceptance criteria verified by `@allure.story("UN-001")` / `@allure.story("UN-002")`
(e.g. out-of-order samples are reordered; stale streams are flagged).
