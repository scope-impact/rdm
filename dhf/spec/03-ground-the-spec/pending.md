# Pending scenarios: slice 03-ground-the-spec

Slice 03 is built in cycle 4 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`sources` and `wiki`, 26 in all, every one in this slice. In this partial
build, check runs the stages that exist and writes
`warning: stage-not-built: STAGE` for each of the others (the paths and the
plugins; the clauses too under `:regulated`), the exit decided by the stages
that ran (`dhf/spec/02-write-the-spec/decisions.md`, ED-02, settled). So a
scenario that runs check is pending only when its Then needs a finding of a
stage a later cycle builds, or a task a later cycle builds; none of these
does (`decisions.md`, ED-01).

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Pending

None.

## Not pending

All 26 are verified in cycle 4: spec-0120, spec-0270 and spec-0269 (ticket
01); spec-0240, spec-0402, spec-0122, spec-0123, spec-0126, spec-0187,
spec-0136, spec-0137 and spec-0199 (ticket 02); spec-0171, spec-0124,
spec-0125, spec-0135 and spec-0138 (ticket 03); spec-0127, spec-0128,
spec-0129, spec-0130, spec-0131 and spec-0132 (ticket 04); spec-0121,
spec-0365 and spec-0236 (ticket 05). Every When runs check, built in cycle 2.
The warnings of the stages not built are warnings, not findings: a Then that
counts findings, or needs exit 0 or 1 (spec-0187, spec-0199, spec-0129),
holds with them. A checkout a scenario names is one its fixture makes
(ED-14); git is needed where the tests run.

No scenario of another slice is unblocked in cycle 4: no pending scenario of
slices 01, 02 or 04 waits for sources or the wiki. KnowledgeIndexed is first
published in this cycle, but the scenarios that list it (spec-0166 and the
bus's, `08-plugins/bus.feature`) are kernel/bus's, built in cycle 5.
