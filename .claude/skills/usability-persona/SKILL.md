---
name: usability-persona
description: Drive a web application's UI as a specified user persona (via Playwright) to produce FORMATIVE usability-validation evidence for a user need - attempt the journey in character, record friction and use errors, and emit a *-persona.json result for `rdm story persona` to ingest. Use when validating a SaMD or web-app user journey, running simulated-use or usability checks, or generating use-error findings. NOT a substitute for summative IEC 62366 usability validation with real representative users.
---

# Usability persona

Drive a web app's UI **as a represented user** to surface usability problems for
one user need, then record the run as formative evidence.

> **Formative only.** This produces *formative* / simulated-use evidence and
> regression of a journey. It is **not** summative IEC 62366-1 validation, which
> requires real representative users. The human summative study remains the
> validation record of truth. Never present this as the validation of record.

## Inputs

- A **persona spec** (e.g. `examples/vitalview-samd/personas/clinician-UN-001.yml`):
  `persona`, `profile`, `user_need` (the UN-id), `goal`, `success` criteria.
- The **app URL** under test.
- The **results directory** to write evidence into (the one `rdm story persona`
  reads, e.g. `persona-results/`).

## Procedure

1. **Get into character.** Read the persona `profile` and `goal`. Behave like
   that user: pursue the goal, do not use insider knowledge of the DOM, and let
   yourself be slowed or confused the way that user would (time pressure,
   gloves, small screen, etc.).
2. **Perceive.** Capture the current screen before acting:
   `python scripts/snapshot.py --url "<URL>" --screenshot step1.png`
   It prints the accessibility tree and saves a screenshot. Read both.
3. **Act, step by step.** Drive the page with Playwright as the persona would
   (click by visible label/role, type into the field you'd actually find).
   Prefer a live browser tool if one is available; otherwise write a short
   Playwright script per step and re-snapshot after each action.
4. **Record friction honestly.** Each time you hesitate, misclick, can't find a
   control, recover from an error, or are surprised — log a usability issue:
   `{ "severity": "difficulty|use_error|confusion|near_miss", "step": <n>, "note": "..." }`.
   This friction *is* the deliverable; do not paper over it.
5. **Judge the outcome** against the `success` criteria: the journey is
   completed (success) or `failed` / `blocked` / `abandoned`.
6. **Write the evidence** (deterministic schema):
   ```
   python scripts/write_evidence.py \
     --results-dir <results-dir> --persona <persona> --user-need <UN-id> \
     --outcome <success|failed|blocked|abandoned> \
     --issues '[{"severity":"confusion","step":4,"note":"..."}]'
   ```
   This writes `<results-dir>/<persona>-<UN>-persona.json`, which
   `rdm story persona` ingests (clean / issues / failed / not_run).

## After the run

- Report: outcome, the usability issues found, and the formative-only caveat.
- These findings feed the **use-related risk analysis** and the inputs to the
  *summative* usability study — they do not close validation.

## Output schema (what the JSON must contain)

```json
{
  "persona": "icu-nurse",
  "user_need": "UN-001",
  "outcome": "success",
  "usability_issues": [
    {"severity": "difficulty", "step": 4, "note": "alert acknowledge control was not obvious"}
  ]
}
```
Always write it via `scripts/write_evidence.py` so the schema stays valid.
