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

It feeds the data that design inputs **DI-1** (alert latency) and **DI-3** (view
vitals) depend on; their acceptance criteria are verified by tests tagged
`@allure.story("DI-1")` / `@allure.story("DI-3")` (e.g. out-of-order samples are
reordered; stale streams are flagged).
