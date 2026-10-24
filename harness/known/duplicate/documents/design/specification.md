---
id: SDS-1
kind: design
context: specification
design_inputs:
  - {id: DI-1, text: "The known record shall have one design input.", traces_to: [UN-001]}
  - {id: DI-1, text: "The same id, declared again.", traces_to: [UN-001]}
---

# Specification

The design gate must fail this record: DI-1 is declared twice.
