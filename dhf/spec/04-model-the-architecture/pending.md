# Pending scenarios: slice 04-model-the-architecture

Slice 04 is built in cycle 3 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`model`, `lint` and `views`, here and in `13-slice-development`, 28 in all.
In this partial build, check runs the stages that exist and writes
`warning: stage-not-built: STAGE` for each of the others, the exit decided by
the stages that ran (`dhf/spec/02-write-the-spec/decisions.md`, ED-02,
settled). So a scenario that runs check is pending only when its Then needs a
finding of a stage a later cycle builds; none of these does. A scenario
whose Then can be observed only through a task a later cycle builds is
pending: verify refuses it. Verify is run anyway and its record kept; every
refusal finding must be a scenario below; the build is graded on the rest;
the spec owner decides on that evidence (`docs/wow/way-of-working.md`, "When
the judge cannot pass a build: pending scenarios"; ED-01). Each is verified
and graded again in the cycle that unblocks it, without slice 04 being
rebuilt.

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Unblocked in cycle 6 (slice 05, verify by results: trace)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0392` A slice is a folder of the spec, holding its plan and one feature file per component it reaches, `13-slice-development/model.feature` | 05 | trace (which component a scenario's file belongs to) |
| `@spec-0394` A configuration with no :spec-layout reads the spec by context, as before, `13-slice-development/model.feature` | 05 | trace (the component that owns the file) |

Both Thens say which component owns a feature file. Check shows only the
negative (no ownership finding, exit 0); a component's ownership of a
scenario is shown by trace, which names the scenario's component. Whether
check's exit 0 with no ownership finding is enough is a question for the
spec owner (`decisions.md`, ED-01); settled that way, both are verified in
cycle 3.

## Not pending

The other 26 scenarios need no task a later cycle builds, and no finding of a
stage a later cycle builds, and are verified in cycle 3: spec-0072,
spec-0227, spec-0073, spec-0074 and spec-0075 (ticket 01); spec-0017,
spec-0198, spec-0018, spec-0077 and spec-0078 (ticket 02); spec-0001,
spec-0002, spec-0003, spec-0005, spec-0006, spec-0007, spec-0008, spec-0009
and spec-0010 (ticket 03); spec-0012, spec-0015 and spec-0339 (ticket 04);
spec-0076, spec-0013, spec-0014 and spec-0393 (ticket 05). The warnings of
the stages not built are warnings, not findings: a Then that counts findings,
or needs exit 0 or 1, holds with them. spec-0017 needs the renderer the stack
names installed where the tests run.

Slice 01's spec-0235 (`01-start-a-project/pending.md`, "Unblocked in cycle
3") is verified in this cycle as a regression item, with ticket 02.
