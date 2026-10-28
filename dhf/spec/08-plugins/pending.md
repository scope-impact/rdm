# Pending scenarios: slice 08-plugins

Slice 08 is built in cycle 5 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`plugins`, `bus` and `oracle`, 27 in all, every one in this slice. In this
partial build, check runs the stages that exist and writes
`warning: stage-not-built: STAGE` for each of the others (the paths; the
clauses too under `:regulated`), the exit decided by the stages that ran
(`dhf/spec/02-write-the-spec/decisions.md`, ED-02, settled). A scenario is
pending when its When runs a task a later cycle builds: this build answers
it as not built (exit 2), so verify refuses it. Verify is run anyway and its
record kept; every refusal finding must be a scenario below; the build is
graded on the rest; the spec owner decides on that evidence
(`docs/wow/way-of-working.md`, "When the judge cannot pass a build: pending
scenarios"; `decisions.md`, ED-01).

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Unblocked in cycle 7 (slice 06, regenerate: fence, verify)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0357` Gate plugins run before fence and verify, in the order declared, and stop them while one fails, `plugins.feature` | 06 | fence |
| `@spec-0358` A Gate plugin is given the task it gates, `plugins.feature` | 06 | verify |
| `@spec-0232` Fence and verify refuse while the oracle fails (2 rows), `oracle.feature` | 06 | fence, verify |
| `@spec-0383` A subscriber to :CycleVerified runs after verify passes, given the cycle, `plugins.feature` | 06 | verify |
| `@spec-0384` A cycle that is not verified publishes nothing, `plugins.feature` | 06 | verify |
| `@spec-0448` A Gate plugin is given the fence it gates, `plugins.feature` | 06 | fence |
| `@spec-0449` The Gate plugins after a failing one are not asked, `plugins.feature` | 06 | fence |
| `@spec-0441` the rows `fence ROOM spec-3` and `verify ROOM 03`, `plugins.feature` | 02 | fence, verify |

## Unblocked in cycle 6 (slice 05, verify by results: status)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0441` the row `status`, `plugins.feature` | 02 | status |

Verify's `:plugin` check, in `verify.feature` beside this file, is
verification/verify's, built whole in cycle 7, and named by slice 06's
tickets, never by this slice's: spec-0361 (3 rows), spec-0391, spec-0362 and
spec-0363. It asks the plugin through `kernel-plugins.ask`, which this slice
builds (ticket 06).

## Not pending

The other 22 scenarios, and spec-0439 to spec-0447 and spec-0450 to
spec-0452 (spec-37b; spec-0441 by its rows `check` and `oracle`), are
verified in cycle 5: spec-0166, spec-0167, spec-0303, spec-0304, spec-0351 and
spec-0353 (ticket 01); spec-0349, spec-0350 and spec-0382 (ticket 02);
spec-0352, spec-0354, spec-0355 and spec-0356 (ticket 03); spec-0342,
spec-0343, spec-0344, spec-0345 and spec-0233 (ticket 04); spec-0359,
spec-0360, spec-0364 (both rows; its package row as spec-37b states it,
G-10) and spec-0405 (ticket 05; ddd-domain alone since spec-37b, G-11). Every When runs check, oracle or plugin add, built in this cycle or
before. The warnings of the stages not built are warnings, not findings.

No scenario of another slice is unblocked in cycle 5: no pending scenario of
slices 01, 02, 03 or 04 waits for the plugins, the bus or the oracle.
