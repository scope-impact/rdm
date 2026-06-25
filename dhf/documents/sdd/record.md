---
id: SDS-REC-001
context: record
satisfies: [UN-001, UN-004]
---

# Record — Software Design

Ingests the system of record so the rest of RDM can compile and gate the DHF.

- `rdm/record/sdd.py` — parse a document's frontmatter; read the user-need
  registry (`user_needs`) and per-context `satisfies` references.
- `rdm/record/allure.py` — parse an Allure results directory into per-user-need
  executed status.
- `rdm/record/verify.py` — build the verification data the DHF renders from.

Contributes to **UN-001** (compile the DHF from the record) and **UN-004**
(verification status traceable from executed results). The layer is dependency
-light (no pydantic / no planning extra). Acceptance criteria verified by
`@allure.story("UN-001")` / `@allure.story("UN-004")` tests.
