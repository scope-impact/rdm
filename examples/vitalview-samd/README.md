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
  design/                               ← one `kind: design` doc per context: inputs (what) + output (how)
    auth.md          owns DI-4,        realises DI-3
    ingestion.md                       realises DI-1, DI-3
    alerting.md      owns DI-1, DI-2
    dashboard.md     owns DI-3,        realises DI-1, DI-2
personas/            ← AI-persona specs (clinician journeys), reference user needs
persona-results/     ← sample formative usability evidence (*-persona.json)
```

## Try it

```bash
# 1. Generate formative usability evidence with the usability-persona skill:
#    invoke the `usability-persona` skill for each spec in personas/, pointing it
#    at the running dashboard URL and --results-dir persona-results. The skill
#    drives the UI via Playwright and writes persona-results/*-persona.json.

# 2. Report it (informational; never gates release):
rdm story persona \
  --vv-plan docs/verification_and_validation_plan.md \
  --persona-results persona-results
```

The evidence JSON is **produced by the `usability-persona` skill**
(`.claude/skills/usability-persona/`), not hand-authored.

User needs (the validated journeys) are defined once in the V&V plan; each
design input traces to the user needs it refines (`traces_to`), so a context
serves the needs its inputs trace to;
acceptance criteria are verified by `@allure`-tagged tests; the journeys are
validated (summatively, by humans) and exercised formatively by AI personas.
See `docs/example-vitalview-decomposition.md`.
