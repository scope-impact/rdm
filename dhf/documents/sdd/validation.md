---
id: SDS-VAL-001
context: validation
satisfies: [UN-005]
---

# Validation — Software Design

Exercises UI usability formatively against a user need.

- `.claude/skills/usability-persona/` — a Claude skill that drives a web UI as a
  represented persona (Playwright), logs friction, and emits a `*-persona.json`
  evidence record via `scripts/write_evidence.py`.
- `rdm/record/persona.py` + `rdm story persona` — ingest those runs into
  per-user-need formative status (clean / issues / failed / not_run).

Contributes to **UN-005**. This evidence is **formative only** — it is not
summative IEC 62366 validation and never gates release; the human summative
study remains the validation record. Acceptance criteria verified by
`@allure.story("UN-005")` tests.
