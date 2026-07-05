---
id: SDS-VAL-001
kind: design
context: validation
satisfies: [UN-005]
design_inputs:
  - id: DI-5
    text: "RDM shall classify AI-persona simulated-use runs into a per-user-need formative status (clean / issues / failed / not_run)."
    traces_to: [UN-005]
  - id: DI-33
    text: "RDM shall ingest per-user-need validation records (user need, disposition, reviewer) from the DHF's validation directory and report, at the release gate, each user need lacking an approved validation record as a warning that does not block release."
    traces_to: [UN-005]
---

# Validation — Software Design

## Design Inputs

This context owns:

- **DI-5 (formative validation)** — classify AI-persona simulated-use runs
  into a per-user-need formative status. Refines UN-005.
- **DI-33 (summative validation records)** — audit finding NC-1: the V&V plan
  declares summative validation per user need but the record held no
  validation artifacts and the gate was silent about it. Summative validation
  records now have a home (`<dhf>/validation/UN-…-validation.json`: user
  need, disposition, reviewer) and the release gate names every user need
  lacking an approved record — as a **warning**, because validation is
  human-evidenced and its absence must be visible without pretending a
  machine can supply it. Refines UN-005.

> **Design property (not a DI clause):** this evidence is *formative only* and
> **never gates release** — the persona reconciler is structurally absent from
> the release gate (a negative/structural property, not mutation-testable; see
> the gating context, where the release gate consults only verification and
> faithfulness).

## Design Outputs

Exercises UI usability formatively against a user need.

- `.claude/skills/usability-persona/` — a Claude skill that drives a web UI as a
  represented persona (Playwright), logs friction, and emits a `*-persona.json`
  evidence record via `scripts/write_evidence.py`.
- `rdm/record/persona.py` + `rdm story persona` — ingest those runs into
  per-user-need formative status (clean / issues / failed / not_run).
- `rdm/record/validation.py` (DI-33) — parse `validation/UN-…-validation.json`
  records; `run_release_gate` reports user needs without an approved record as
  warnings.

This evidence is **formative only** — it is not summative IEC 62366 validation
and never gates release; the human summative study remains the validation record.
Acceptance criteria are verified by `@allure.story("DI-5")` tests. Note the
persona ingest stays **user-need keyed** — validation is anchored on the user
need, not the design input.
