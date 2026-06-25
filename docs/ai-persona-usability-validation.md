# AI-persona usability validation (formative)

> Status: partial implementation. The **record side** (evidence ingest + report)
> is built; the **Playwright driver** runs against the device UI, out of this
> repo (RDM has no UI).

## What this is — and is not

An **AI persona** is an LLM that drives the device UI as a represented user
(e.g. an ICU nurse), attempting a **user-need journey** and recording what
happened. It produces evidence tagged to the user need.

**It is formative, not summative.** Summative usability validation
(IEC 62366-1) requires **real, representative users** in simulated use. An AI
persona is not a representative user and **cannot be the validation record of
truth.** Legitimate uses:

- **Formative evaluation** — surfaces use errors and usability problems early.
- **Continuous simulated-use regression** — did a UI change break the journey?
- **Use-error hypotheses** feeding the use-related risk analysis (IEC 62366).

The human summative study remains the validation record. Treating persona output
as summative validation is a regulatory and patient-safety error.

## Where it sits
```
user need (V&V plan)
  ├─ verified by  → @allure tests (acceptance criteria)              [automated]
  └─ validated by → ① human summative HF study   (record of truth)   [human]
                    ② AI-persona simulated-use run                    [formative, this]
                          │ emits *-persona.json tagged to the user need
                          ▼
                    record/persona.py → per-need formative status → `rdm story persona`
```

## The evidence contract (what the driver emits)
One `*-persona.json` per run, in a results directory:

```json
{
  "persona": "icu-nurse",
  "user_need": "UN-001",
  "goal": "Notice and acknowledge a dangerous SpO2 drop",
  "outcome": "success",            // success | failure | blocked | abandoned
  "usability_issues": [
    {"severity": "difficulty", "step": 3, "note": "alarm mute control hard to find"}
  ]
}
```

`record/persona.py` reconciles these against the user-need registry (read from
the V&V plan) into a per-need status:

| Status | Meaning |
|--------|---------|
| `clean` | completed, no issues observed (**not** "validated") |
| `issues` | completed, usability problems observed |
| `failed` | a persona could not complete the journey |
| `not_run` | no persona attempted this user need |

```
rdm story persona --vv-plan dhf/documents/verification_and_validation_plan.md \
                  --persona-results ./persona-results
```

This is **informational** — formative findings inform the use-related risk
analysis; they do not gate release (verification does; summative validation is
human).

## The driver: the `usability-persona` skill
The persona run is performed by the **`usability-persona` Claude skill**
(`.claude/skills/usability-persona/`) rather than hand-authored JSON. Given a
persona spec (persona profile from the IFU + the user-need goal) and the app
URL, the skill drives the UI via Playwright: the LLM perceives (the bundled
`scripts/snapshot.py` dumps the accessibility tree + a screenshot) and acts
(click/type) **in character** until goal/fail/timeout, logging friction as it
goes. It then writes the `*-persona.json` above via `scripts/write_evidence.py`
(deterministic schema). RDM only ingests the result. Playwright runs wherever
the UI does (Claude Code has full network/browser access; an `rdm[validation]`
extra could carry it for other surfaces).

### Persona spec (example)
```yaml
persona: icu-nurse
user_need: UN-001
profile: "ICU nurse, time-pressured, frequent interruptions, gloved hands"
goal: "Notice and acknowledge a dangerous SpO2 drop within 10 s"
success: ["alarm acknowledged", "correct patient confirmed"]
```
