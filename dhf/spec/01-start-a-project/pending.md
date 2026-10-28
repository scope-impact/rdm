# Pending scenarios: slice 01-start-a-project

Slice 01 is built first, whole (ADR 0006, "Build order"), so its components
carry every scenario they own in any slice: the scenarios of `command`, `init`,
`adopt` and `hooks`, here and in `11-project-in-a-folder`. A scenario whose
When runs a task a later cycle builds is pending: this build answers that task
as not built (exit 2, the command's stand-in for a slice not built yet), so
verify refuses it. Verify is run anyway and its record kept; every refusal
finding must be a scenario below; the build is graded on the rest; the spec
owner decides on that evidence (`docs/wow/way-of-working.md`, "When the judge
cannot pass a build: pending scenarios"; ED-01). Each is verified and graded
again in the cycle that unblocks it, without slice 01 being rebuilt.

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Unblocked in cycle 6 (slice 05, verify by results: status)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0179` A task that cannot run exits 2 and says why, `command.feature` | 04 | status |
| `@spec-0193` A task's results directory defaults to the configured one, `command.feature` | 04 | status |
| `@spec-0219` A task's results directory is --results, else RESULTS_DIR, else the configured one (3 rows), `command.feature` | 04 | status |

spec-0179's exit 2 is the command's own refusal of a results directory that
does not exist (ticket 04), settled before the task is run; whether the
command refuses it before a task not built answers is ED-16. Settled that way,
spec-0179 passes in this cycle and is verified here.

## Unblocked in cycle 7 (slice 06, regenerate: check's paths stage; fence, verify, scopes)

check has no gate: every stage of it runs and reports (spec-0235, the
glossary's "Gate"), and a stage not built refuses the task. Its stages come
with cycle 2 (the spec check, slice 02), cycle 3 (the model lint and the
views' freshness, slice 04), cycle 4 (sources and wiki, slice 03), cycle 5
(the plugins that subscribe to what it reads, slice 08) and cycle 7 (the
committed paths, slice 06's fence). The clauses stage (slice 10, cycle 10)
runs only where the configuration holds `:regulated`, which none of these
projects does; if it counts as a stage everywhere, these move to cycle 10
(ED-17).

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0177` A task that passes exits 0, `command.feature` | 04 | check, every stage |
| `@spec-0178` A task that finds a problem exits 1 and names it on the error stream, `command.feature` | 04 | check, every stage |
| `@spec-0235` A task with no gate reports the findings of every stage, `command.feature` | 04 | check, every stage (the missing level: slice 02; the stale view: slice 04) |
| `@spec-0090` The check passes on a skeleton nobody has touched, `init.feature` | 04 | check, every stage |
| `@spec-0368` A task that reads a cut reads the project's folder at the cut, rows `fence`, `verify`, `scopes`, `11-project-in-a-folder/command.feature` | 05 | fence, verify, scopes |

## Unblocked in cycle 8 (slice 07, measure: checklist)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0368` the row `checklist 03`, `11-project-in-a-folder/command.feature` | 05 | checklist |

spec-0368 is verified whole in cycle 8, its three other rows from cycle 7.

## Not pending

The other 15 scenarios need no task a later cycle builds, and are verified
in cycle 1: spec-0176 and spec-0180 (ticket 01); spec-0086, spec-0272,
spec-0087, spec-0089, spec-0223 and spec-0025 (ticket 02; spec-0025's check is
whichever `rdm-spec` the scenario puts on its path, ED-11); spec-0079 to
spec-0085 (ticket 03).
