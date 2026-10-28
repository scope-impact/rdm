# Pending scenarios: slice 07-measure

Slice 07 is built in cycle 8 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`checklist`, `scorer`, `scoreboard` and `observe`, 40 in all (31 in this
slice, 2 in `10-regulated-profile`, 1 in `11-project-in-a-folder`, 1 in
`12-several-specs`, 5 in `13-slice-development`). A scenario is pending when
its When runs a task a later cycle builds, or its Then needs a component a
later cycle builds (`decisions.md`, ED-01).

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Unblocked in cycle 10 (slice 10, the regulated profile: gap analysis)

| Scenario | Ticket | What it needs |
|---|---|---|
| `@spec-0266` Each clause of the cut's clause lists is an item after the scenarios, `10-regulated-profile/checklist.feature` | 03 | compliance/gap's clause index (ED-04): until then the checklist of a regulated project scoped to reach compliance cannot run (exit 2) |

## Other slices' scenarios unblocked in cycle 8

Pending in their slices since earlier cycles, verified in this one with this
slice's checklist, without their slices being rebuilt:

| Scenario | Slice, ticket | Task it needs | This slice's ticket |
|---|---|---|---|
| `@spec-0220` A task that names a cut reads the spec as it was at that cut, `parser.feature` | 02, ticket 06 | checklist | 01 |
| `@spec-0368` A task that reads a cut reads the project's folder at the cut, row `checklist 03`, `11-project-in-a-folder/command.feature` | 01, ticket 05 | checklist | 01 |

spec-0368 is then verified whole, its three other rows from cycle 7
(`06-regenerate/pending.md`).

## Not pending

39 are verified in cycle 8: spec-0019, spec-0210, spec-0211 and spec-0406
(ticket 01); spec-0173, spec-0200, spec-0389, spec-0395, spec-0414,
spec-0396 and spec-0417 (ticket 02; the verify their Whens also run is
slice 06's, built in cycle 7); spec-0271 (ticket 03: a scope away from
compliance asks no clause index); spec-0174, spec-0142, spec-0143,
spec-0144, spec-0214 and spec-0418 (ticket 04); spec-0212, spec-0379,
spec-0380, spec-0213, spec-0022, spec-0023 and spec-0201 (ticket 05);
spec-0020, spec-0021, spec-0225, spec-0192, spec-0283 and spec-0284
(ticket 06); spec-0286 to spec-0292 (ticket 07); spec-0293 (ticket 08).
Their visible gaps (`decisions.md`, "Spec gaps") are stated before the
build, or each scenario is graded on what it states alone.
