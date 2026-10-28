# Decisions: slice 07-measure

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `checklist.feature`, Rule: The checklist is derived from the cycle's cut

The checklist is derived from the spec, never written by hand
(loyly:README.md@17d68d2, "What to steal" 1).

### `checklist.feature`, Rule: An unstamped scenario stops the checklist

A scenario the checklist cannot name would shrink the denominator without
anyone noticing (loyly:harness/spec.feature@17d68d2, "An unstamped scenario
stops the checklist rather than shrinking it").

### `observe.feature`, Feature: Observe

Every run of a cycle, a builder's, a grader's or the integrator's, has two
halves: its capability, what it was for, proven by its result (ratify, the
score), and its defence, what it did outside its brief. "Done" is not the
builder's claim (loyly:harness/README.md@17d68d2, "The idea in five lines"
3), and neither is "stayed in my room". Builders and graders wrote at the
filesystem's root, beside their rooms and into /tmp in cycles 06 to 11, and
each was found only because someone looked (dhf/cycles/retrospective-01-12.md).

This component observes: it records, it never prevents, and it never stops
a run. Reads cannot be observed, and the record says so.

The configuration's :agency {:watch [DIR ...] :tmp DIR} names the
directories whose entries are compared before and after a run ("~" is the
home directory) and the temporary directory whose entries are counted. W1,
T and ROOM are directories the scenario makes.

### `observe.feature`, Rule: Two cycles' runs are compared side by side, never netted

Löyly's scoreboard counts both directions and never the net
(loyly:harness/README.md@17d68d2, "The pieces, in pipeline order"); so is
defence, and it is never folded into the score.

### `scoreboard.feature`, Rule: A scoped cycle is reported target first

A partial regeneration can break what it did not rebuild; the scoreboard
shows that apart from what it did. A scoped cycle's checklist holds only
its scope, so the rest is compared only where both cycles scored it, as a
project cycle does (cycle 02, dhf/cycles/02/proposed-spec-changes.md, A1).

### `scoreboard.feature`, Scenario: A scoped cycle is compared over its scope only (spec-0225)

spec-0172 (a scoped cycle that scored every scenario) is retired: under
the scope rule no such cycle can exist (cycle 03, A1).

### `scoreboard.feature`, Rule: A bootstrap cycle is never a baseline

Before any rdm-spec release exists, the first build is ratified by a reviewed
bootstrap cycle (ADR 0002). It says so, and nothing is measured against it.

### `scoreboard.feature`, Rule: The scoreboard's result has one shape

The scoreboard's result was pinned only by the oracle's goldens, which no
builder sees, and the first rebuild of the scoreboard since they were
recorded drifted from them in 25 places (dhf/cycles/11/proposed-spec-changes.md,
1). The shape is the spec's, as the goldens record it.

### `scorer.feature`, Feature: Scorer

Scoring is independent of the builder's tests, as in Löyly: a grader, human
or agent and never the builder, grades each checklist item against the
verified build, however they grade, and the scorer files the verdicts
(loyly:README.md@17d68d2, "3. Measure").

### `scorer.feature`, Scenario: The verdicts of several files are filed as one (spec-0379)

spec-28: two graders grade one cycle in two rooms; their files are scored
as one. A file that is not readable EDN names where it stops. How the
graders' rooms are built is the project's own (its grading, like its
oracle, is a project's judge, ADR 0003).

### `scorer.feature`, Rule: The instrument refuses to record what it did not measure

Incident: a scoring run whose every batch failed on a network outage once
committed "scored" over an empty record (loyly:harness/README.md@17d68d2,
"The failure modes this shape was learned from").

### `scorer.feature`, Rule: The builder neither grades nor reads its own score

Every role may be a human or an agent (UN-012), so the instrument cannot
rely on "a person" to stay out of the loop. What it relies on is that the
one who grades and the one who reads the score are not the one who built
(loyly:harness/README.md@17d68d2, "The idea in five lines").

