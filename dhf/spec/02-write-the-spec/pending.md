# Pending scenarios: slice 02-write-the-spec

Slice 02 is built in cycle 2 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`parser`, `stamper` and `check`, here, in `12-several-specs` and in
`13-slice-development`, 32 in all. In this partial build, check runs the
stages that exist and writes `warning: stage-not-built: STAGE` for each of
the others, the exit decided by the stages that ran (ED-02, settled by the
spec owner). So a scenario that runs check is pending only when its Then
needs a finding of a stage a later cycle builds; none of these does. A
scenario whose When runs a task a later cycle builds is pending: this build
answers that task as not built (exit 2), so verify refuses it. Verify is run
anyway and its record kept; every refusal finding must be a scenario below;
the build is graded on the rest; the spec owner decides on that evidence
(`docs/wow/way-of-working.md`, "When the judge cannot pass a build: pending
scenarios"; ED-01). Each is verified and graded again in the cycle that
unblocks it, without slice 02 being rebuilt.

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Unblocked in cycle 6 (slice 05, verify by results: trace)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0047` A need tag on the feature is inherited by every scenario under it, `check.feature` | 03 | trace |

## Unblocked in cycle 8 (slice 07, measure: checklist)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0220` A task that names a cut reads the spec as it was at that cut, `parser.feature` | 06 | checklist |

## Not pending

The other 30 scenarios need no task a later cycle builds, and no finding of a
stage a later cycle builds, and are verified in cycle 2: spec-0051 to
spec-0053 (ticket 01); spec-0054 to spec-0058 (ticket 02); spec-0042,
spec-0043, spec-0186, spec-0234, spec-0044, spec-0045, spec-0046, spec-0150,
spec-0048, spec-0049, spec-0134, spec-0050 and spec-0209 (ticket 03);
spec-0385 to spec-0388 (ticket 04); spec-0400, spec-0401, spec-0411,
spec-0412 and spec-0413 (ticket 05). The warnings of the stages not built are
warnings, not findings: a Then that counts findings, or needs exit 0 or 1,
holds with them.
