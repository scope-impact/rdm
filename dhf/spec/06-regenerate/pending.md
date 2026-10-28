# Pending scenarios: slice 06-regenerate

Slice 06 is built in cycle 7 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`cut`, `scopes`, `fence` and `verify`, 67 in all (54 in this slice, 4 in
`08-plugins`, 3 in `11-project-in-a-folder`, 1 in `12-several-specs`, 5 in
`13-slice-development`). In this build, check runs every stage of a project
without `:regulated` (its paths stage is this slice's, ticket 01); under
`:regulated` it warns `stage-not-built: clauses` until cycle 10
(`dhf/spec/02-write-the-spec/decisions.md`, ED-02, settled). A scenario is
pending when its When runs a task a later cycle builds, or its Then needs a
stage a later cycle builds (`decisions.md`, ED-02).

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Pending

None. Every When of the 67 runs cut, scopes, fence, verify or check, built in
this cycle or before; none runs under `:regulated`. spec-0216's, spec-0315's
and spec-0410's fixtures need only their own `:verify` commands; spec-0404's
the slices layout (slice 04, cycle 3).

## Other slices' scenarios unblocked in cycle 7

Pending in their slices since earlier cycles, verified in this one with
this slice's tasks, without their slices being rebuilt:

| Scenario | Slice, ticket | Task it needs | This slice's ticket |
|---|---|---|---|
| `@spec-0368` A task that reads a cut reads the project's folder at the cut, rows `fence`, `verify`, `scopes`, `11-project-in-a-folder/command.feature` | 01, ticket 05 | fence, verify, scopes | 03, 06, 05 |
| `@spec-0403` A scenario's tag labels are its feature's, its rule's and its own, in that order, `runner.feature` | 05, ticket 07 | verify (its last Then: the tests check) | 09 |
| `@spec-0357` Gate plugins run before fence and verify, in the order declared, and stop them while one fails, `plugins.feature` | 08, ticket 06 | fence | 03 |
| `@spec-0448` A Gate plugin is given the fence it gates, `plugins.feature` | 08, ticket 06 | fence | 03 |
| `@spec-0449` The Gate plugins after a failing one are not asked, `plugins.feature` | 08, ticket 06 | fence | 03 |
| `@spec-0232` Fence and verify refuse while the oracle fails (2 rows), `oracle.feature` | 08, ticket 06 | fence, verify | 03, 06 |
| `@spec-0358` A Gate plugin is given the task it gates, `plugins.feature` | 08, ticket 06 | verify | 06 |
| `@spec-0441` the rows `fence ROOM spec-3` and `verify ROOM 03`, `plugins.feature` | 08, ticket 02 | fence, verify (verify's row as G-21 settles) | 03, 06 |
| `@spec-0383` A subscriber to :CycleVerified runs after verify passes, given the cycle, `plugins.feature` | 08, ticket 06 | verify | 10 |
| `@spec-0384` A cycle that is not verified publishes nothing, `plugins.feature` | 08, ticket 06 | verify | 10 |

spec-0368 stays pending on its row `checklist 03` until cycle 8 (slice 07),
as `01-start-a-project/pending.md` says.

## Not pending

All 67 are verified in cycle 7: spec-0296 and spec-0297 (ticket 01);
spec-0036, spec-0162, spec-0224 and spec-0163 (ticket 02); spec-0037,
spec-0038, spec-0294, spec-0295, spec-0182 and spec-0369 (ticket 03);
spec-0183, spec-0184, spec-0226, spec-0329, spec-0185, spec-0222 and
spec-0397 (ticket 04); spec-0237, spec-0241, spec-0238, spec-0298,
spec-0348, spec-0340, spec-0299, spec-0407, spec-0398, spec-0399 and
spec-0416 (ticket 05); spec-0202, spec-0203, spec-0204, spec-0205,
spec-0217, spec-0316 and spec-0370 (ticket 06); spec-0206, spec-0207,
spec-0197, spec-0033, spec-0208, spec-0218, spec-0216, spec-0315, spec-0410
and spec-0404 (ticket 07); spec-0317, spec-0322, spec-0314, spec-0215,
spec-0318, spec-0366, spec-0367, spec-0319, spec-0320, spec-0346, spec-0321
and spec-0341 (ticket 08); spec-0331, spec-0347, spec-0332 and spec-0333
(ticket 09); spec-0361, spec-0391, spec-0362 and spec-0363 (ticket 10).
Their visible gaps (`decisions.md`, G-01 to G-05, G-07 to G-13, G-16 to
G-32) are stated before the build, or each scenario is graded on what it
states alone.

With the paths stage built, no check of any slice warns
`stage-not-built: paths` any more: a scenario of an earlier slice whose
fixture commits a path its configuration neither denies nor keeps now has a
finding, and is a regression item of this cycle.
