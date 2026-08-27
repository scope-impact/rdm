---
id: RDM-004
title: 'Wayfinder: lock the ALM and risk data model for rdm'
status: To Do
assignee: []
created_date: '2026-08-27 05:54'
updated_date: '2026-08-27 05:57'
labels:
  - 'wayfinder:map'
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
## Destination

The target ALM + risk data model's open questions are all closed and recorded as rdm design inputs, with a migration plan ready to execute. This map produces decisions and the plan — not the migration.

## Notes

Domain: medical-device ALM with ISO 14971 risk modelling. Source model is the "Simplified data model — medical device ALM with risk modelling" draft (Aligned Elements concepts, normalised, cross-referenced against ReqIF / StrictDoc / Doorstop / Kiwi TCMS).

Skills every session should consult: `domain-modeling` and `grilling` for the decision tickets, `risk-analysis` for anything touching the risk layer, `story-audit` for the traceability half, `backlog` for map mechanics.

Only some of these are linked into the harness skill list. All of them exist in the `scope-impact/agent-skills` checkout — read them directly from `skills/engineering/<name>/SKILL.md`, except `grilling` and `backlog` which are under `skills/productivity/`. A session that cannot invoke `/grilling` should read that file and follow it rather than skipping the HITL exchange: every ticket on this map except the research one is human-in-the-loop, and an agent that answers its own grilling questions has broken the ticket.

**Settled before this map was charted** — do not reopen:

- **Markdown-first.** Markdown is the authoring surface; the relational model is a derived, validated projection. Measured: 12 of 14 target risk tables already project out of the existing registers, and all 14 initial scores recompute from the RMP-001 matrix.
- **No ID or format unification.** halla-health's `RC-`/`RISK-` ids were already in rdm's canonical prefix vocabulary and the audit still found zero, so renaming solves nothing. Both design-doc discriminators (`kind: design`, `type: sdd`) are carried as a superset instead.
- **halla-health-infra is the reference implementation for risk traces** — its four clusters carry 95 `(refs: …)` control→requirement traces across 104 controls. The wallet's register carries 0. rdm's parser already supports the mechanism.
- **Plan only.** Execution hands off as a separate effort once this map closes.

**Why the schema is not intact today.** rdm's `risks` table flattens `hazard`/`situation`/`harm` into VARCHAR columns and carries `severity`/`probability`/`risk_level`/`residual_risk` as a before/after column pair — both shapes the source model explicitly rejects. `risk_controls.description` is a string, so measures have no identity and no reuse. Absent entirely: `CAUSE`, `PROBABILITY_OF_HARM`, `MEASURE_EFFECT`, `RISK_SCALE`/`RISK_LEVEL`/`RISK_POLICY`, `REVISION`, `SNAPSHOT`. Present and working: `TRACE` via `risk_requirements` + `risk_controls.refs`.

## Decisions so far

<!-- one line per closed ticket: gist, then zoom the link -->

## Not yet specified

- **P1/P2 adoption.** Whether `PROBABILITY_OF_HARM` requires the ISO 14971 decomposition, and what happens to the 34 existing risks (20 infra, 14 wallet) scored with a single probability. Hangs on the fold algorithm.
- **CAUSE separation.** How the markdown splits today's single `### Hazard` section into cause and situation, and what the parser does with it.
- **ReqIF interchange.** Whether import/export from DOORS or Polarion is in scope at all; if so, whether entity names move to ReqIF's (`SpecObject`, `SpecType`, `AttributeDefinition`, `SpecRelation`).
- **Which rdm design inputs and contexts the locked decisions become.** Needs most decisions closed first; `story_audit` (SDS-AUDIT-001) and `record` (SDS-REC-001) are the likely homes.
- **The migration plan itself** — assembled once the decisions above are closed. The final artifact of this map.
- **How the repo-by-repo audit sequences** after the map closes: infra first (already conformant), then the wallet, then documentation.

## Out of scope

- Executing the migration — writing the DuckDB migration, changing the parser, adding tagged acceptance tests and faithfulness verdicts. The destination is the locked schema plus the plan; execution is a separate effort.
- Changing the halla-health risk registers. They are the evidence this map reasons from, not its subject.
<!-- SECTION:DESCRIPTION:END -->
