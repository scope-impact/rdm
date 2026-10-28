# Decisions: slice 06-regenerate

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `cut.feature`, Rule: A cut is a pushed tag on the default branch, and regeneration reads only a cut

At a fixed time the spec is cut, tagged and frozen, and a regeneration reads
that and nothing else (loyly:harness/README.md@17d68d2, "The idea
in five lines" 1).

### `cut.feature`, Rule: Every merge to the default branch is cut, once

Decided by the event storm's hotspot H2 (dhf/storm/2026-10-24-rdm.md): every
merge to the default branch is cut, by CI, so a cut never waits on anyone.

### `cut.feature`, Scenario: A HEAD whose spec fails the check is not cut (spec-0224)

Incident: spec-2 was cut holding views older than its own model, so its
regeneration could not ratify (cycle 02, A2).

### `fence.feature`, Rule: A clean room is built only from a cut

Every prior implementation and every measurement is out of the clean room,
so nothing can be copied, only rebuilt (loyly:harness/README.md@17d68d2, "The idea in five lines" 2).

### `fence.feature`, Rule: A clean room is the cut minus what is denied

Incident: a weaker fence probe once passed while the previous implementation
was still reachable from an older commit (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
The clean room is the cut minus what is denied, as Löyly's fence is the
repository minus every implementation and measurement (spec owner, cycle
13). A deny-list leaks what nobody remembered to deny; so every committed
path is classified, denied or kept, and an unclassified one stops the
check, and no kept file may carry a removed implementation file's bytes.
spec-0040 (an allowed path missing at the cut) is retired with the
allow-list.

### `fence.feature`, Rule: A clean room can rebuild the project, one bounded context or one component

The configuration's :code-layout places each component's code and unit
tests by context and component, and :implementation lists every
implementation path. So any part can be rebuilt while the rest stands, which
is what makes the contexts composable.

### `fence.feature`, Scenario: A component the cut removed is fenced by the files the code layout finds for it (spec-0329)

scopes lists a component the cut removed, and fence refused it, so its
code was removed from cycle 16's room by hand
(dhf/cycles/16/proposed-spec-changes.md, 3).

### `fence.feature`, Scenario: The kernel is a scope like a bounded context (spec-0222)

The kernel is shared code in no bounded context, but it changes with the
spec like any other (cycle 01, D).

### `scopes.feature`, Feature: Scopes

Which scopes a cycle regenerates is read from the spec, never remembered:
cycle 04 missed a changed component when its scopes were listed by hand
(dhf/cycles/04/proposed-spec-changes.md, D).

### `scopes.feature`, Scenario: A changed file no component owns is named for a person to judge (spec-0238)

A shared file is a changed, kept path that no component owns; it is
named as a warning, and the task exits 0.

### `scopes.feature`, Rule: A changed event reaches its publisher and every subscriber

A changed event reaches its publisher and every subscriber the model
names for it. Reading :read-by per key, and per key of a map, saved a few
scopes and broke twice: cycle 14 read its first appearance as every key
added, cycle 16 regressed on a flat list under a nested key. The spec
owner chose Löyly's simplicity over the saving (spec-22; about 5 to 15%
more build cost): :read-by is gone, and a whole-project cycle every few
cycles catches what scoping hides (README, "A whole-project cycle").

### `scopes.feature`, Scenario: A changed publisher reaches both publishers and every subscriber (spec-0348)

An event's publisher is part of its contract: the subscribers' events
now come from another component (spec owner, spec-24).

### `scopes.feature`, Scenario: A change that leaves an event's contract unchanged reaches no component (spec-0340)

Removing :read-by from every event reached every subscriber of every
event: spec-21 to spec-22 listed 15 scopes (cycle 18, integrator). An
event's contract is its publisher, what it carries and their shape.

### `scopes.feature`, Rule: Scopes are listed in the order a builder builds them

One builder rebuilds a cycle's scopes one after another (spec owner, cycle
13), so the list is in the order they can be built.

### `verify.feature`, Feature: Verify

Verify is the gate: what a builder claims is done is decided by checks the
builder does not control, as Löyly's verify.sh decides it
(loyly:harness/README.md@17d68d2, "The idea in five lines" 3). It was named
ratify until spec-20; the record it writes keeps its name, ratified.edn, so
every cycle's record reads the same and the oracle's goldens still hold.

### `verify.feature`, Rule: Verify trusts only the cut

The clean room is only where the builder works. What is judged is the
room's implementation, put into a fresh checkout of the cut, so the spec
and the configuration always come from the cut, never from the room, as
Löyly verifies impl/ in its trusted repository
(loyly:harness/README.md@17d68d2, "The idea in five lines" 3).

### `verify.feature`, Rule: The tests check passes only when the tests pass and every scenario passed

Incident: a gate once reported success over a test command that had become
a no-op exiting 0 (loyly:harness/README.md@17d68d2, "The failure modes this
shape was learned from").

### `verify.feature`, Scenario: A scenario no test names fails verification (spec-0208)

Every scenario is tested before it counts as built (loyly:docs/wow/invariants.md@17d68d2, INV-5).

### `verify.feature`, Scenario: A failed or broken result fails verification (spec-0218)

Incident: a gate once passed while a step failed, because only the
command's exit status was read (cycle 00, dhf/cycles/00/proposed-spec-changes.md, A1).

### `verify.feature`, Rule: Verify records what it verified

The grader grades the build that was verified, and only that build.

### `verify.feature`, Rule: A project declares its checks, and verify runs every one the same way

What verifying means depends on what the project is: a served app proves
it serves, a command proves it answers, a library has its tests (spec
owner, after the Löyly comparison, dhf/cycles/loyly-comparison.md, finding
1: ratify proved the results and never that the app came up). So a project
declares its checks, and rdm-spec guarantees how every check runs. The
kinds are closed: a new kind is a spec change, never a configuration.

### `verify.feature`, Scenario: A check whose command is not found refuses the gate (spec-0317)

Incident: a missing configuration turned a gate's test command into a
no-op exiting 0 (loyly:harness/README.md@17d68d2, "The failure modes this
shape was learned from"). A check that cannot run is never a pass.

### `verify.feature`, Rule: A :serves check proves the app comes up and is this application

Löyly's gate boots the app fresh and polls it inside a named budget, and
reports the margin, so a boot creeping toward the limit is seen before it
fails (loyly:harness/README.md@17d68d2, "The gate's boot budget").

### `verify.feature`, Scenario: A probe is given the app's url, ending in "/", and has its own time limit (spec-0366)

Löyly's cycle 04: its probe built "//" paths from a url that ends in
"/", and the release stopped it at the check's whole budget, reporting
"exited ," with nothing it printed; three cuts to diagnose (spec owner,
spec-26).

### `verify.feature`, Scenario: A port held only by the closing connections of an app that has gone is free (spec-0346)

A port whose last app has gone but whose connections the system still
holds for a minute (TIME_WAIT) was refused as held (cycle 18, grader).

### `verify.feature`, Scenario: An app that accepts connections but never sends an HTTP response does not answer (spec-0341)

Cycle 16's build counted any HTTP response to GET / as an answer, cycle
17's an accepted connection; the spec said neither (cycle 17, builder).

### `verify.feature`, Rule: The tests check holds every result to the project's Allure mapping

The results are the oracle of a project's scenarios; the mapping says how
each one carries the spec, and verify holds every result to it, so a test
that claims a scenario it does not follow fails the gate instead of
waiting for a grader (spec owner, after the Löyly comparison,
dhf/cycles/loyly-comparison.md, cycle 02: the builder had to decide six
things the contract left unsaid).

### `verify.feature`, Scenario: A planned scenario's result is held to its spec label only (spec-0347)

Cycle 18's verify demanded a trace from a @wip scenario, which is never
run and so can carry no evidence; the grader gave 72 of 72, and the
held-still oracle found it on its first run (dhf/harness/oracle/README.md).

