---
id: SDS-INGEST-001
kind: design
context: ingestion
satisfies: [UN-001, UN-002]
design_inputs: []        # owns none; feeds inputs owned by other contexts
realises: [DI-1, DI-3]
---

# Ingestion — Software Design

## Design Inputs

This context owns no design inputs of its own. It **realises** **DI-1** (alert
latency, owned by `alerting`) and **DI-3** (view vitals, owned by `dashboard`) by
feeding them fresh, ordered, validated vitals.

## Design Outputs

Receives streamed vital signs from monitor gateways, validates and timestamps
them, and stores current and recent values per patient.

Contributes to **UN-001** (alerting and the dashboard need fresh, ordered vitals
to act on) and **UN-002** (the dashboard reads current and recent vitals from
here). The realised inputs are verified by tests tagged `@allure.story("DI-1")` /
`@allure.story("DI-3")` (e.g. out-of-order samples are reordered; stale streams
are flagged).
