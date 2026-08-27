---
id: RDM-004
title: 'Wayfinder: lock the ALM and risk data model for rdm'
status: To Do
assignee: []
created_date: '2026-08-27 05:54'
updated_date: '2026-08-27 06:45'
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

**Do not trust `backlog task list --ready` on this map.** In backlog.md 1.50.1 it behaves as "has no dependencies" rather than its documented "all dependencies completed": with RDM-004.05 closed, RDM-004.06 stayed off the `--ready` listing even though its only dependency was Done. Wayfinder calls `--ready` load-bearing, and this is the worse direction of failure — as tickets close, their dependents never join the frontier, so the map silently reads as smaller than it is and looks finished while blocked work remains. Compute the frontier from the task files instead:

```bash
uv run python - <<'EOF'
import yaml, pathlib
T = {}
for p in pathlib.Path('backlog/tasks').glob('rdm-004*.md'):
    fm = yaml.safe_load(p.read_text().split('---')[1]); T[fm['id']] = fm
for i, fm in sorted(T.items()):
    if i == 'RDM-004' or fm['status'] != 'To Do' or fm.get('assignee'): continue
    if all(T.get(d, {}).get('status') == 'Done' for d in (fm.get('dependencies') or [])):
        print(i, '-', fm['title'])
EOF
```

**Why the schema is not intact today.** rdm's `risks` table flattens `hazard`/`situation`/`harm` into VARCHAR columns and carries `severity`/`probability`/`risk_level`/`residual_risk` as a before/after column pair — both shapes the source model explicitly rejects. `risk_controls.description` is a string, so measures have no identity and no reuse. Absent entirely: `CAUSE`, `PROBABILITY_OF_HARM`, `MEASURE_EFFECT`, `RISK_SCALE`/`RISK_LEVEL`/`RISK_POLICY`, `REVISION`, `SNAPSHOT`. Present and working: `TRACE` via `risk_requirements` + `risk_controls.refs`.

## Decisions so far

<!-- one line per closed ticket: gist, then zoom the link -->

- [Decide what a mitigation's effect attaches to](backlog/tasks/rdm-004.01%20-%20Decide-what-a-mitigations-effect-attaches-to.md) — `MEASURE_EFFECT` references `PROBABILITY_OF_HARM` (harm pathway), decided on intent rather than evidence since the estate's 34 situations each carry exactly one harm and make the two options isomorphic today; one `MEASURE` with several effect rows; effects derived with explicit override, reducing-or-neutral, targeting p1/p2 and never severity; residuals provisional. Recorded as [decision-001](backlog/decisions/decision-001%20-%20Mitigation-effects-attach-to-the-harm-pathway-not-the-hazardous-situation.md).
- [Research how ReqIF and StrictDoc express types as data](backlog/tasks/rdm-004.05%20-%20Research-how-ReqIF-and-StrictDoc-express-types-as-data.md) — neither reference implementation validates instances against their types, so borrowing ReqIF's names buys interchange and no validation; and neither has a usable query surface for a data-modelled type. Findings in [docs/research/reqif-strictdoc-typing.md](docs/research/reqif-strictdoc-typing.md).

## Not yet specified

- **P1/P2 adoption.** Narrowed by RDM-004.01: the decomposition exists, `p2_level` is optional, and effects may target p1 or p2 but never severity. What remains is how a markdown author expresses p1 and p2 separately at all, since no register carries either today.
- **CAUSE separation.** How the markdown splits today's single `### Hazard` section into cause and situation, and what the parser does with it.
- **ReqIF interchange.** Whether import/export from DOORS or Polarion is in scope at all; if so, whether entity names move to ReqIF's (`SpecObject`, `SpecType`, `AttributeDefinition`, `SpecRelation`). Narrowed by RDM-004.05: adopting the names is cheap and buys no validation, since ReqIF cannot express a mandatory field and its schema declares no referential integrity. The remaining question is round-trip fidelity, not vocabulary.
- **Which rdm design inputs and contexts the locked decisions become.** Needs most decisions closed first; `story_audit` (SDS-AUDIT-001) and `record` (SDS-REC-001) are the likely homes.
- **The migration plan itself** — assembled once the decisions above are closed. The final artifact of this map.
- **How the repo-by-repo audit sequences** after the map closes: infra first (already conformant), then the wallet, then documentation.

## Out of scope

- Executing the migration — writing the DuckDB migration, changing the parser, adding tagged acceptance tests and faithfulness verdicts. The destination is the locked schema plus the plan; execution is a separate effort.
- Changing the halla-health risk registers. They are the evidence this map reasons from, not its subject.
- Re-modelling the 34 under-modelled risks so that a hazardous situation carries each of its several harms. Ruled out while resolving [Decide what a mitigation's effect attaches to](backlog/tasks/rdm-004.01%20-%20Decide-what-a-mitigations-effect-attaches-to.md): the decision that 1:1 is not honest makes those registers wrong, but fixing them is authoring work on halla-health, not schema work on rdm. A named follow-on effort, not a step on this route.
<!-- SECTION:DESCRIPTION:END -->
