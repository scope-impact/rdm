# Pending scenarios: slice 05-verify-by-results

Slice 05 is built in cycle 6 (ADR 0006, "Build order"), whole, so its
components carry every scenario they own in any slice: the scenarios of
`runner`, `results`, `status`, `trace` and `release`, 54 in all (47 in this
slice, 2 in `10-regulated-profile`, 3 in `12-several-specs`, 2 in
`13-slice-development`). A scenario is pending when it needs a task or a
stage a later cycle builds: this build answers it as not built, so verify
refuses it. Verify is run anyway and its record kept; every refusal finding
must be a scenario below; the build is graded on the rest; the spec owner
decides on that evidence (`docs/wow/way-of-working.md`, "When the judge
cannot pass a build: pending scenarios"; `decisions.md`, ED-01).

Cycles are those of ADR 0006's build order. Each scenario stays named by its
ticket.

## Unblocked in cycle 7 (slice 06, regenerate: verify)

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0403` A scenario's tag labels are its feature's, its rule's and its own, in that order, `runner.feature` | 07 | verify (its last Then: verify's tests check holds the result to these tags) |

## Unblocked in cycle 10 (slice 10, the regulated profile: gap analysis)

Under `:regulated`, release asks the clauses stage, the not-built stand-in's
until slice 10 is built, and a release cannot run without it (ED-07).

| Scenario | Ticket | Task it needs |
|---|---|---|
| `@spec-0267` The release check refuses a clause no audit tag names, `10-regulated-profile/release.feature` | 05 | release's clauses stage (compliance/gap) |
| `@spec-0278` The release check refuses a controlled document with no id, `10-regulated-profile/release.feature` | 05 | release's clauses stage (compliance/gap) |

## Pending on a decision or a spec gap

Each is verified in cycle 6 if it is settled before the build, and waits
for the cycle that settles it otherwise.

| Scenario | Ticket | What it waits for |
|---|---|---|
| `@spec-0168`, `@spec-0169`, `@spec-0170`, `@spec-0032`, `@spec-0155`, `@spec-0156`, `@spec-0324`, `runner.feature` | 06 | G-01 and G-02 (ED-02): the step protocol, stated by spec-39 in this file's rule and the glossary; a grader writes step files in it |
| `@spec-0151`, `@spec-0152`, `@spec-0153`, `@spec-0154`, `@spec-0308`, `@spec-0309`, `@spec-0310`, `@spec-0311`, `@spec-0323`, `runner.feature` | 07 | G-01, as above (stated by spec-39) |
| `@spec-0108` Without results, the trace says no status was derived, `trace.feature` | 04 | ED-09: the command's refusal of a configured results directory that does not exist, for trace |
| `@spec-0324` Results go to the configured results directory when RESULTS_DIR is not set, `runner.feature` | 06 | ED-09 and G-03: run's results directory when it does not exist |

## Other slices' scenarios unblocked in cycle 6

Pending in their slices since earlier cycles, verified in this one with
this slice's tasks, without their slices being rebuilt:

| Scenario | Slice, ticket | Task it needs |
|---|---|---|
| `@spec-0179` A task that cannot run exits 2 and says why, `command.feature` | 01, ticket 04 | status |
| `@spec-0193` A task's results directory defaults to the configured one, `command.feature` | 01, ticket 04 | status |
| `@spec-0219` A task's results directory is --results, else RESULTS_DIR, else the configured one (3 rows), `command.feature` | 01, ticket 04 | status |
| `@spec-0047` A need tag on the feature is inherited by every scenario under it, `check.feature` | 02, ticket 03 | trace, with no results directory (ED-09, as spec-0108) |
| `@spec-0392` A slice is a folder of the spec, holding its plan and one feature file per component it reaches, `13-slice-development/model.feature` | 04, ticket 05 | trace |
| `@spec-0394` A configuration with no :spec-layout reads the spec by context, as before, `13-slice-development/model.feature` | 04, ticket 05 | trace |

## Not pending

The other 34 scenarios are verified in cycle 6: spec-0061, spec-0062 and
spec-0313 (ticket 01); spec-0307, spec-0064, spec-0191, spec-0065,
spec-0066, spec-0302, spec-0285, spec-0067, spec-0068, spec-0069, spec-0070,
spec-0060, spec-0063, spec-0164 and spec-0312 (ticket 02); spec-0408 and
spec-0419 (ticket 03); spec-0102, spec-0103, spec-0104, spec-0105,
spec-0279, spec-0106, spec-0107 and spec-0409 (ticket 04); spec-0059,
spec-0188, spec-0189, spec-0190, spec-0390 and spec-0415 (ticket 05). Every
When runs status, trace or release, built in this cycle, on a project
without `:regulated`. Their visible gaps (G-13 to G-26) are stated before
the build, or each scenario is graded on what it states alone.
