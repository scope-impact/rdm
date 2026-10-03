# Validation evidence

Verification asks whether each design input is met; validation asks whether
each user need is. RDM keeps two kinds of validation evidence, both recorded
against a user need.

## Human validation (the record of truth)

A person's validation judgment is a file in the record,
`<dhf>/validation/UN-…-validation.json`, with `user_need`,
`disposition: "approved"`, `reviewer` (required: a person makes the
judgment) and `summary`. The release gate names every user need without one, as a warning: a machine cannot supply that
judgment, but its absence is never silent.

## AI personas (formative only)

An **AI persona** is a model that drives the product's UI as a represented
user (an ICU nurse, say), attempting a user need's journey and recording what
happened. It is **formative, not summative**: summative usability validation
(IEC 62366-1) needs real, representative users, so a persona run can never be
the validation record. It is good for finding use errors early, for checking
that a UI change did not break a journey, and for use-error hypotheses that
feed the use-related risk analysis.

The `usability-persona` skill (`.claude/skills/usability-persona/`) does the
run: given a persona spec and the app's URL, it drives the UI with Playwright
in character until it reaches the goal, fails or times out, and writes one
`*-persona.json` per run:

```yaml
# the persona spec
persona: icu-nurse
user_need: UN-001
profile: "ICU nurse, time-pressured, frequent interruptions, gloved hands"
goal: "Notice and acknowledge a dangerous SpO2 drop within 10 s"
success: ["alarm acknowledged", "correct patient confirmed"]
```

```json
{
  "persona": "icu-nurse",
  "user_need": "UN-001",
  "goal": "Notice and acknowledge a dangerous SpO2 drop",
  "outcome": "success",
  "usability_issues": [
    {"severity": "difficulty", "step": 3, "note": "alarm mute control hard to find"}
  ]
}
```

`outcome` is one of `success`, `failure`, `blocked` or `abandoned`. RDM
reconciles the runs against the user needs in the V&V plan:

```bash
rdm story persona --vv-plan dhf/documents/verification_and_validation_plan.md \
  --persona-results persona-results/
```

| Status | Meaning |
|--------|---------|
| `clean` | completed, no issues observed (**not** "validated") |
| `issues` | completed, usability problems observed |
| `failed` | a persona could not complete the journey |
| `not_run` | no persona attempted this user need |

Across several runs of one need, `failed` outranks `issues`, which outranks
`clean`. A run file that cannot be read as a run — no user need, issues that
are not a list, JSON that does not parse — is listed as unreadable, never
counted as clean. `usability_issues` stays a list; an entry of plain text in
it still counts as an issue. A run
naming a user need the registry does not hold is listed as an orphan.

It is informational and never gates a release.
