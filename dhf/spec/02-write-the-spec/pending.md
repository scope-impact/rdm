# Pending scenarios: slice 02-write-the-spec

Slice 02 is built in cycle 2 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`parser`, `stamper` and `check`, here, in `12-several-specs` and in
`13-slice-development`, 32 in all. A scenario whose When runs a task a later
cycle builds, or runs check before every stage of it is built, is pending:
this build refuses it (exit 2: the task not built, or check's stage not
built, ED-02), so verify refuses it. Verify is run anyway and its record
kept; every refusal finding must be a scenario below; the build is graded on
the rest; the spec owner decides on that evidence (`docs/wow/way-of-working.md`,
"When the judge cannot pass a build: pending scenarios"; ED-01). Each is
verified and graded again in the cycle that unblocks it, without slice 02
being rebuilt.

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Unblocked in cycle 6 (slice 05, verify by results: trace)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0047` A need tag on the feature is inherited by every scenario under it, `check.feature` | 03 | trace |

## Unblocked in cycle 7 (slice 06, regenerate: check's paths stage)

check has no gate: every stage of it runs and reports (spec-0235), and a
stage not built refuses the task (the glossary's "Gate"; ED-02). Its stages
come with cycle 2 (the spec read and the spec check, this slice), cycle 3
(the model's lint and the views' freshness, slice 04), cycle 4 (sources and
the wiki, slice 03), cycle 5 (the plugins that subscribe to what it reads,
slice 08) and cycle 7 (the paths, slice 06). The clauses stage (slice 10) runs
only where the configuration holds `:regulated` (slice 01's ED-17), which
none of these projects does. So every scenario below runs check to exit 0 or
1 from cycle 7.

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0051` A feature file using every supported construct passes the check, `parser.feature` | 01 | check, every stage |
| `@spec-0052` An unknown keyword is refused with its line, `parser.feature` | 01 | check, every stage |
| `@spec-0053` A feature file holds exactly one feature, `parser.feature` | 01 | check, every stage |
| `@spec-0042` A scenario with two ids fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0043` An id used by two scenarios fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0186` A scenario with no id fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0234` An id the ledger never issued fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0044` Each level is accepted (2 rows), `check.feature` | 03 | check, every stage |
| `@spec-0045` A scenario with no level fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0046` A scenario with two levels fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0150` A tag that is not an id, a level, a need or @wip fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0048` A scenario that traces to no need fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0049` A need tag naming no declared need fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0134` A user need citing no source fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0050` The spec check accepts a scenario tagged @wip, `check.feature` | 03 | check, every stage |
| `@spec-0209` A spec file naming a configured stack word fails the spec check, `check.feature` | 03 | check, every stage |
| `@spec-0385` Several specs are the configuration's :specs, each a folder of its own, `12-several-specs/parser.feature` | 04 | check, every stage |
| `@spec-0387` Each spec is checked on its own terms, and one spec's finding fails the check, `12-several-specs/check.feature` | 04 | check, every stage |
| `@spec-0400` A slice's plan says its goal, its scope and what it rests on, `13-slice-development/check.feature` | 05 | check, every stage |
| `@spec-0401` A slice that is not planned is a finding (4 rows), `13-slice-development/check.feature` | 05 | check, every stage |
| `@spec-0411` A slice's status is one of four, under its plan's title (6 rows), `13-slice-development/check.feature` | 05 | check, every stage |
| `@spec-0412` A slice holding planned work is not done, `13-slice-development/check.feature` | 05 | check, every stage |
| `@spec-0413` Every slice keeps its decisions, `13-slice-development/check.feature` | 05 | check, every stage |

Under ED-02's option B (a stage not built answering no finding, a spec
change), these 23 would be verified in cycle 2.

## Unblocked in cycle 8 (slice 07, measure: checklist)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0220` A task that names a cut reads the spec as it was at that cut, `parser.feature` | 06 | checklist |

## Not pending

The other 7 scenarios need no task or stage a later cycle builds, and are
verified in cycle 2: spec-0054 to spec-0058 (ticket 02); spec-0386, refused
before anything runs (ticket 04, under ED-03), and spec-0388 (ticket 04).
