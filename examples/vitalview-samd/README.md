# VitalView — Remote Patient Monitoring Dashboard (SaMD)

A worked **software-only** example (Software as a Medical Device, web app) for
the record-first model — chosen because it has a real **user interface** an
AI persona can drive (see `docs/ai-persona-usability-validation.md`), which a
firmware device does not.

VitalView is a clinician-facing web dashboard that ingests streamed vital signs,
raises alerts on dangerous changes, and presents them to authorized clinicians.

## Record-first layout

```
docs/
  verification_and_validation_plan.md   ← user_needs registry (UN-001..003) + V&V approach
  architecture.md                       ← system design (bounded contexts), NO need listing
  sdd/
    auth.md          satisfies: [UN-002, UN-003]
    ingestion.md     satisfies: [UN-001, UN-002]
    alerting.md      satisfies: [UN-001]
    dashboard.md     satisfies: [UN-001, UN-002]
personas/            ← AI-persona specs (clinician journeys), reference user needs
persona-results/     ← sample formative usability evidence (*-persona.json)
```

## Try it

```bash
# Formative usability evidence from AI-persona runs (informational; never gates):
rdm story persona \
  --vv-plan docs/verification_and_validation_plan.md \
  --persona-results persona-results
```

User needs (the validated journeys) are defined once in the V&V plan; each
bounded-context SDD references the user needs it contributes to via `satisfies`;
acceptance criteria are verified by `@allure`-tagged tests; the journeys are
validated (summatively, by humans) and exercised formatively by AI personas.
See `docs/example-vitalview-decomposition.md`.
