# Decisions: slice 05-verify-by-results

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `results.feature`, Rule: Allure results are the oracle, whoever wrote them

Incident: a test runner named its suites twice, once in imports and once in
a run list, so a forgotten suite was silently green
(loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").

### `results.feature`, Scenario: A results file that cannot be read is named, never skipped (spec-0062)

Cycle 16's reader relayed the parser's message, which held a newline, so
the finding took two lines (dhf/cycles/16/proposed-spec-changes.md, 2;
README, "How every task reports").

### `results.feature`, Rule: What decides a status, and what is only kept

A result is read for what status, trace and verify depend on, and every
other Allure element is kept: an attachment, a link, a parameter are
evidence a person reads in the report, never what decides a status.

### `runner.feature`, Feature: Gherkin runner

Kernel component Gherkin runner: dhf/spec/model.edn, :kernel.

Decided by the event storm's hotspot H10 (dhf/storm/2026-10-24-rdm.md): the
runner is kernel code, so any project whose step definitions are written for
it can use it. A project in another stack uses its own runner and only has
to write the same Allure results.

### `runner.feature`, Rule: Step definitions are written against one small API

rdm-spec run is offered to any project whose step definitions are written
for it, so the way a step is defined is a public contract.

### `runner.feature`, Scenario: A failing step fails the result and names the step (spec-0153)

spec-0153 and spec-0310 said a step that throws fails the result;
spec-0323 says only a Then whose assertion does not hold is failed
(cycle 17, grader).

### `runner.feature`, Rule: The runner discovers, and refuses to find nothing

Incident: a test runner named its suites twice, once in imports and once in
a run list, so a forgotten suite was silently green (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").

### `runner.feature`, Rule: Every result is written in Allure's own model, from its Gherkin scenario

rdm-spec reads Allure results and writes them; it never invents a format
beside Allure's. Each element a result carries is Allure's own, filled from
the Gherkin scenario, so an Allure report is one more view of the same run,
and a project in any stack can write the same results with its own
Allure-Gherkin adapter (spec owner, after the Löyly comparison,
dhf/cycles/loyly-comparison.md, finding 2: a builder could not learn the
result format from its own project).

### `runner.feature`, Scenario: Failed is a Then that did not hold; broken is anything else that went wrong (spec-0323)

Two of the six things cycle 02's builder of the Löyly app decided alone
(dhf/cycles/loyly-comparison.md, cycle 02).

### `runner.feature`, Rule: A scenario's tags are its own and those it inherits

spec-32, from cycle 24: cycles 19 to 23 wrote a tag label for every tag a
scenario inherits from its feature and its rule, cycle 24 only for its
own, and each build's verify refused the other's results; spec-0308 said
"one per Gherkin tag" and no more. A need tag sits on a feature or a rule
(README.md, "Conventions"), so the scenario's inherited tags are the ones
that trace it; Allure's own Gherkin adapters write them too.

### `status.feature`, Rule: Status's result has one shape

Every other task prints its result under its own name, and status printed
its map bare, a shape no scenario stated (cycle 14, builder; README, "A
task's result shape is the spec's").

### `status.feature`, Scenario: Results that name no row do not verify an outline (spec-0302)

An outline with fewer results than rows, all passing, counted as
verified (cycle 11, builder): a release could pass on rows never run.
Three results naming no row verified a three-row outline (cycle 13,
grading): re-runs of one row could pass a release.

### `status.feature`, Rule: Allure results are the oracle, whoever wrote them

Incident: a test runner named its suites twice, once in imports and once in
a run list, so a forgotten suite was silently green
(loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").

### `trace.feature`, Rule: The trace's result has one shape

Cycle 08: spec/check's step read trace's result in one shape and the
rebuilt trace wrote another; the shape is now the spec's.

