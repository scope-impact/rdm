# VitalView demo app

A tiny, self-contained mock of the VitalView dashboard (single HTML file, no
dependencies) so the `usability-persona` skill has a real UI to drive. It covers
the journeys behind UN-001 (notice + acknowledge a critical alert) and UN-002
(sign in, find a patient, read vitals). Sign in with any username and password
`demo`.

## Serve it

```bash
python -m http.server 8099   # then open http://localhost:8099/
```

## Generate persona evidence

- **The real way** — invoke the `usability-persona` skill against
  `http://localhost:8099/` for each spec in `../personas/`. The skill drives the
  UI in character, logs friction, and writes `../persona-results/*-persona.json`.
- **Reference smoke** — `python run_persona_demo.py` runs the same journeys
  deterministically (no agent) to prove the loop end-to-end.

Then report (informational; never gates):

```bash
rdm story persona \
  --vv-plan ../docs/verification_and_validation_plan.md \
  --persona-results ../persona-results
```

The app intentionally contains minor UX friction (a terse "Ack" button; a
"Filter" box where a user expects "Search") so a persona run surfaces realistic
formative findings.
