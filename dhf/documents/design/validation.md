---
id: SDS-VAL-001
kind: design
context: validation
satisfies: [UN-005]
design_inputs:
  - id: DI-5
    text: "RDM shall support exercising UI usability formatively against a user need, recorded as evidence that never gates release."
    traces_to: [UN-005]
---

# Validation — Software Design

## Design Inputs

This context owns **DI-5 (formative validation)** — support exercising UI
usability formatively against a user need, recorded as evidence that never gates
release. Refines UN-005.

## Design Outputs

Exercises UI usability formatively against a user need.

- `.claude/skills/usability-persona/` — a Claude skill that drives a web UI as a
  represented persona (Playwright), logs friction, and emits a `*-persona.json`
  evidence record via `scripts/write_evidence.py`.
- `rdm/record/persona.py` + `rdm story persona` — ingest those runs into
  per-user-need formative status (clean / issues / failed / not_run).

This evidence is **formative only** — it is not summative IEC 62366 validation
and never gates release; the human summative study remains the validation record.
Acceptance criteria are verified by `@allure.story("DI-5")` tests. Note the
persona ingest stays **user-need keyed** — validation is anchored on the user
need, not the design input.
