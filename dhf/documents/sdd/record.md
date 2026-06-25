---
id: SDS-REC-001
context: record
satisfies: [UN-001, UN-004]
---

# Record — Software Design

Ingests the system of record so the rest of RDM can compile and gate the DHF.

- `rdm/record/sdd.py` — parse a document's frontmatter; read the user-need
  registry (`user_needs`), the design-input registry (`design_inputs`), and
  per-context `satisfies` references.
- `rdm/record/allure.py` — parse an Allure results directory into per-design-input
  executed status.
- `rdm/record/verify.py` — build the verification data the DHF renders from.

Contributes to **UN-001** (compile the DHF from the record) and **UN-004**
(verification status traceable from executed results). This context realises
design input **DI-1** (record ingest, no planning dependency); the layer is
dependency-light (no pydantic / no planning extra). Its acceptance criteria are
verified by `@allure.story("DI-1")` tests.
