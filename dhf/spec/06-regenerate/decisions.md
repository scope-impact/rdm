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


## Expected decisions (planned for the component build, ADR 0006)

Accepted as recommended by the integrator on 2026-10-28, under the spec
owner's goal; the spec owner may overrule any. Among them ED-01 (cut runs
check as a process; cut and fence instantiated on their own in
rdm-spec.wac) and ED-04 (the model gains scopes and verify reading cuts
with cut). The visible spec gaps, with spec-0362's paths a :plugin check
cannot resolve and spec-0176's usages lacking --slice and --spec, are
stated in the next spec change before this slice is built.

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`, cycle 7 of its build order;
`docs/wow/harness.md`, "The planner"): each question a builder of this slice
would otherwise decide alone, with its options and the planner's
recommendation. The spec owner settles each before the build, or turns it
into a scenario. Where the spec is silent on what rdm-spec does, the
question is a spec gap (below), not a decision. The tickets (`ticket-01` to
`ticket-10`) cite them as ED-NN and G-NN; other slices' are cited as "slice
08's ED-NN" and so on. The architecture is in `dhf/wit/`
(`regeneration-cut.wit`, `regeneration-scopes.wit`, `regeneration-fence.wit`,
`verification-verify.wit` with the event record `verify-events`,
`slice-06.wit`, `slice-06.wac`). The settlements of slices 01 to 05 and 08
hold here: no bridge (their ED-01), a stage not built is a warning (slice
02's ED-02), events are values handed on as EDN between slices (slice 02's
ED-04, slice 08's ED-02), a task's interface uses only its own and builtin
types (slice 01's ED-14), fence and verify ask the Gate themselves (slice
08's ED-03).

### ED-01: The loop between check and this slice, and where cut and fence are instantiated

Slice 02's check imports this slice's paths stage (fence's: spec-0296,
spec-0297), and this slice reads slice 02: cut runs check (spec-0224), and
verify reads the spec at the cut with the parser (spec-0217, spec-0207,
spec-0331). One instance of the slice cannot come both before slice 02 and
after it (slice 02's ED-02 foresaw it). The model has fence resolve cuts
with cut's git helpers.

- A. cut runs check as a command, `RDM_SPEC check` through `rdm:host/process`
  in the project's root (RDM_SPEC as slice 08's ED-07 settles it), and
  relays its lines (spec-0224 asks for check's finding lines unchanged): it
  imports nothing of slice 02. cut and fence, which import only the host,
  each other, kernel/plugins (slice 08) and architecture/model (slice 04),
  are instantiated on their own in `rdm-spec.wac`, ahead of slice 02, as
  slice 05's ED-07 instantiates compliance/gap for slice 10; fence's paths
  stage is wired by name into slice 02's import. The slice's composition
  (`slice-06.wac`) holds scopes, verify and the tasks, after slices 02 and
  05, importing cut's and fence's interfaces from their instances. Cost: a
  cut needs RDM_SPEC (absent, it cannot run, saying so), and one process
  more.
- B. cut imports `spec-check` and lives in the slice's composition; fence,
  on its own ahead of slice 02, resolves cuts through git itself: the
  model's two relationships from fence to cut are unserved, and the cut's
  git helpers are written twice.
- C. The paths stage becomes a component of its own in the model (a spec
  change), instantiated ahead of slice 02; the rest of the slice in one
  instance.
- D. The whole slice in one instance after slice 02, the paths stage left to
  the not-built stand-in: check warns `stage-not-built: paths` for good and
  spec-0296 and spec-0297 never pass.

Recommendation: A. `rdm-spec.wac` then gains three instances and three
wiring lines instead of one and one (ticket 01 gives them as text), and
rewrites none. Composed by the planner against stand-in components built
from the declarations: the slice alone, and the command with slices 01, 02,
03, 04, 08, 05 and 06 (the not-built stand-in with `include
check-stages-not-built;`).

### ED-02: Scenarios whose task another slice builds

As slices 01 to 08, settled there: no bridge. This slice's components carry
67 scenarios: 54 in this slice (cut 4, scopes 7, fence 13, verify 30),
4 in `08-plugins` (verify's `:plugin` check), 3 in `11-project-in-a-folder`,
1 in `12-several-specs` and 5 in `13-slice-development`. None needs a task a
later cycle builds: check, its stages without `:regulated`, the parser, the
results reader and the plugins are built (cycles 2 to 6). This cycle unblocks
earlier slices' pending scenarios, verified as regression items without
those slices being rebuilt (`pending.md`).

- A. None pending; the earlier slices' pending scenarios of cycle 7 verified
  in this cycle.

Recommendation: A.

### ED-03: How fence, verify and scopes reach git through the host

A component sees files through the host's standard interfaces (the project's
folder and `/`, slice 01's ED-05), which carry no permission bit, and runs
git only through `rdm:host/process`, whose `run` takes the whole standard
input and returns the whole output. spec-0038 wants the cut's committed
files unchanged and no object of a denied path; spec-0294 and spec-0369
nothing untracked or ignored; spec-0202 an export with no history; spec-0370
the room's implementation as git would commit it, links as links.

- A. **Plumbing, files written by the core.** A cut is read with `git
  rev-parse` and `git tag --points-at` (resolve), `git ls-tree -r -z` (its
  tree: mode, object, path, within the project's folder), `git cat-file
  --batch` (its blobs, one process for all), `git diff --name-status -z`
  (scopes' changes); the core writes each file through the standard
  interfaces, a link as a link, and sets the executable bit with `chmod`
  through the host (as slice 01's ED-04 sets the hook's). fence then makes
  the room's one root commit in ROOM (`git init`, `git add -A`, `git commit`
  with hooks off), so only kept objects are ever in it. The room's
  implementation reaches verify's checkout from `git ls-files -co
  --exclude-standard -z` in the room (its own repository; a temporary git
  directory when it has none, G-27), copied with its links and modes by
  `tar -c --null -T -` into `tar -x`, the archive passing through the
  core. scopes writes each cut out to a temporary directory under the
  TMPDIR rdm-spec was given, read there with architecture/model, removed
  before it returns, also on failure.
- B. `git archive | tar -x`: fewer processes, but archive applies the
  export-ignore and export-subst attributes, so a kept file can differ from
  its committed bytes (spec-0038).
- C. `git worktree add` or a checkout: smudge filters and line-ending
  conversion change bytes, and the room or checkout is linked to the
  project's object store, its history reachable (spec-0038, spec-0202).
- D. `git show CUT:PATH`, one process per file: slow on a whole cut.

Recommendation: A, with unit tests on a fixture repository holding an
executable file, a link, a file with an export-subst attribute and an
ignored file.

### ED-04: Where the git helpers live

fence "Resolves cuts with the git helpers of" cut (the model); scopes reads
two cuts and verify one, and the model relates both only to the repository.

- A. `regeneration-cut` exports them (`resolve`, `tree`, `show`, `extract`,
  `changes`), and fence, scopes and verify import it. The model gains
  "Reads cuts with the git helpers of" from scopes and verify to cut in the
  next spec change.
- B. A copy in each crate: three readings of a cut.
- C. The kernel library (slice 01's ED-13): slice 01's crate, outside this
  cycle's fence.

Recommendation: A.

### ED-05: Reading scopes by slice

spec-0398 makes a touched slice a scope with every component it reaches;
spec-0416 reaches a component through any of its feature files, wherever its
spec path is; spec-0399 lists a component two touched slices share in the
first only; spec-0397 and spec-0404 fence and verify a slice named alone on
every component it reaches.

- A. A slice reaches the components that own a feature file in its folder
  (architecture/model's `owners` on the cut written out, ED-03): the
  to-cut's, and the from-cut's for a file the change deleted, so a
  component the cut removed is still named (spec-0329). scopes lists the
  touched slices in the order of their folders' names, each with the
  components it reaches that no earlier slice of the same listing holds,
  in build order (spec-0299: the kernel first, in the model's order, then
  each publisher before its subscribers by the model's event relationships,
  ties in the model's order). "The first only" is a rule of one listing:
  fence `--slice` and a cycle scoped to a slice (`:scope [{:slice S}]`, or
  `:slice S`, spec-0417) reach every component the slice reaches, whatever
  another slice holds. One function of the order, shared by fence's
  removal and scopes' listing, unit-tested on spec-0299's model.
- B. The first-only rule also in fence and verify: a slice cycle would then
  depend on which other slice its cut touched.

Recommendation: A. Events under the slices layout are G-10.

### ED-06: How verify runs a project's checks through the host

Every check runs in the checkout, `dhf/cycles/NN/build`.

- A. `:tests`: one `run`, its command, RESULTS_DIR the absolute path of the
  cycle's results directory in the project's repository (spec-0206), no
  time limit (the check declares no budget); its standard output then its
  error stream written to `dhf/cycles/NN/test.log` (G-33). `:runs`: one
  `run` with `timeout-ms` its budget; its exit and first line held to
  `:expect`; its margin the budget less its `millis`, to 0.01 (spec-0322,
  spec-0314). `:serves`: its port tried first by binding 127.0.0.1:PORT
  with the standard interfaces' sockets and releasing it, held when the
  address is in use (spec-0320), the runtime's bind reusing an address only
  closing connections hold (spec-0346, a unit test); its app started with
  `spawn` (`{port}` filled in), polled K times, poll i at i x S/K seconds
  after the start, each a connection sending `GET /` and answered when an
  HTTP status line comes back before the next poll is due (spec-0341); then
  its probe one `run` with `{url}` filled in as `http://127.0.0.1:PORT/` and
  `timeout-ms` its `:probe-seconds` (30 by default), what it printed kept
  (spec-0366, spec-0367); the app stopped with `stop`, its process group
  with it, on every path out of the check, verify's own failures included
  (spec-0318, spec-0319). `:plugin`: kernel-plugins' `ask` (G-24). A
  command the host cannot start (`cannot-start`) refuses the gate, exit 2,
  naming the check and the command (spec-0317).
- B. Polls with `curl` through the host: a tool neither the stack nor the
  configuration names.
- C. An HTTP function in `rdm:host`: the host grows for what the core can
  do.

Recommendation: A. The app's standard streams are not verify's: the host's
`spawn` returns no output, so it gives the app none of verify's (G-35).

### ED-07: The paths stage

Check hands each stage its arguments, the project's folder and the run's
events; the paths stage reads the configuration's `:clean-room`,
`:implementation` and the committed paths, and nothing of the events.

- A. fence's `unclassified-paths` on the index (cut's `tree` with no
  revision, ED-04; what "committed" is, G-07), one finding per path,
  `FILE: RULE: neither denied nor kept`, no line, the rule the component's
  (spec-0296, spec-0297); it publishes nothing and reports nothing
  (check's result stays the lint's, spec-0423). A configuration with no
  `:clean-room` holds every committed path unclassified.
- B. The stage reads HEAD's tree only (G-07).

Recommendation: A, settled with G-07.

### ED-08: How verify reads the cycle's results

verification/results (slice 05) reads a directory's Allure results: each
one's file, spec label, status, parameters, and its text as read; every file
it cannot read; and refuses (an error) a directory that does not exist or
holds no result.

- A. verify reads `dhf/cycles/NN/results` with `read` after the tests
  command exits 0: its error is spec-0033's finding (exit 1, "a green exit
  with no results is not a run"), never a cannot-run; each stamped scenario
  of the cycle's scope (G-23) named by a result whose spec label is its id,
  passed when every result naming it passed (G-26 for an outline's rows);
  an unreadable file fails the check, naming it (G-25). The mapping
  (spec-0331, spec-0333) is held on each result's `json`, parsed by verify,
  against the scenario as the parser reads it at the cut (ED-09): name,
  steps (the background's first), labels, links and attachments. A
  `:plugin` check's directory, `dhf/cycles/NN/results/NAME`, is read the
  same way (spec-0391), a refusal for no result meaning the plugin wrote
  none: judged by its exit alone.
- B. verify reads the Allure files itself: a second reader, and the model's
  "Reads the tests check's results with" unserved.

Recommendation: A.

### ED-09: How verify reads the spec at the cut

- A. `spec-parser.parse` at the cut and the project's folder (slice 02: it
  reads a revision through git): every scenario with its ids, tags, steps,
  background, rows, rule, needs, context and component; a scenario with no
  id is spec-0217's refusal. The checkout is never parsed in place (the
  parser reads the project's root).
- B. The parser pointed at the checkout: a parser change in slice 02.

Recommendation: A.

### ED-10: The order of verify's steps

spec-0202 has verify read the cycle "before anything else it does";
kernel-plugins' `gate` is "asked before the gated task does anything else",
and its request carries the cut, which only cycle.edn names (spec-0358).

- A. cycle.edn (spec-0203); the plugins' declaration at the project's root
  (spec-0441); the Gate (spec-0232: a failing answer relayed, exit 1, no
  ratified.edn); the checkout, fresh (G-22); the cut's configuration held
  (spec-0316, spec-0332, spec-0363: exit 2, no check, no ratified.edn); the
  spec at the cut (spec-0217: exit 1, ratified.edn refused); the checks in
  order, the first that fails or is refused stopping them (spec-0315,
  G-29); ratified.edn; CycleVerified delivered when every check passed
  (spec-0383, spec-0384). fence in the same spirit: the declaration, the
  Gate (its `:cut` as given), then the cut and the scope.
- B. The Gate before cycle.edn: the request has no cut.

Recommendation: A, with G-21.

### ED-11: Where CycleVerified's record lives

- A. As slice 05's ED-05: an interface of its own, `verify-events`, exported
  by verification/verify beside `verification-verify`, written in
  `verification-verify.wit`; `events.wit`'s header comment names it in the
  cycle that builds the slice. It reaches the plugins as EDN through
  kernel-plugins' `deliver` (slice 08's ED-02, ED-03).

Recommendation: A.

### ED-12: Who exports the slice's tasks

- A. As slice 02's ED-05: a composition component, `regenerate-tasks`,
  holding no logic: the table's rows of cut, scopes, fence and verify
  exactly as spec-0176 writes them (slice 01's ED-18), each run with its
  component.

Recommendation: A.

### ED-13: The host's standard interfaces, and asynchronous functions

- A. As slice 02's ED-08: the cycle that builds the slice adds to `regenerate`
  and each component world the one line slice 01's worlds carry, in the
  version they carry then (it brings the sockets a `:serves` check uses).
  The functions of this slice's interfaces that run processes are
  asynchronous, as `tasks`' `run` and the host's `run` are since the move of
  cycle 26; the paths stage keeps check's stage interface as
  `spec-check.wit` writes it.

Recommendation: A.

## Spec gaps

What the spec leaves unstated about this slice's components. For each: the
scenario or rule, what is unstated, the smallest statement that would close
it, and whether it must be stated before the build (VISIBLE: a grader could
see it) or may stay the builder's choice (FREE: no grader could).

### Cut

- **G-01** (spec-0036, spec-0162). cut's result on standard output, and
  spec-0162's exit. Smallest: a contract scenario, e.g. `{:cut {:tag
  "spec-7" :commit C :pushed true}}`, and `{:cut {:tag "spec-7" :already
  true}}` with exit 0. VISIBLE.
  **Stated by spec-43:** spec-0478, `{:cut {:tag :commit :tagged true :pushed "origin"}}`, and `{:cut {:tag :commit :tagged false :says S}}` with exit 0, as the release prints.
- **G-02** (spec-0036). The first cut's name with no spec-N tag yet, and
  which tags count: every tag `spec-N` of the repository, N decimal, the
  next one more than the highest. Smallest: "the first cut is spec-1; N is
  one more than the highest N of the repository's spec-N tags". VISIBLE.
  **Stated by spec-43:** spec-0479 and the glossary's "Cut": the first is spec-1; N one more than the highest decimal N of the tags `spec-N`, any other tag ignored, as the release.
- **G-03** (spec-0224). Where cut runs check (the working tree in the
  project's root, or HEAD as committed), and what it does when check cannot
  run (exit 2) or warns. Smallest: "check is run in the project's root; its
  exit 2 is cut's, relaying its line; its warnings are relayed after its
  findings". VISIBLE. Recommendation: as written (ED-01).
  **Stated by spec-43:** spec-0480: check is run in the project's root; its cannot-run line is relayed unchanged with its exit 2, its warnings relayed and the cut made.
- **G-04** (spec-0163, spec-0036). No `origin` or no `origin/HEAD`, and a
  push that fails: the tag left or removed, and the exit. Smallest: "exit 2
  naming what is missing; a push that fails removes the tag and exits 2,
  naming it". VISIBLE.
  **Stated by spec-43:** spec-0481: no origin, or no origin/HEAD, exit 2 naming what is missing; a push that fails removes the tag, exit 2, relaying the push's message. Chosen differently from the release, which said only "not the commit origin/HEAD names" for a missing origin or origin/HEAD.
- **G-05** (spec-0162, spec-0163, spec-0224). The order of cut's refusals
  when several hold. Recommendation: not origin/HEAD, then already cut (no
  check run), then the check. VISIBLE only on a combined fixture.
  **Stated by spec-43:** spec-0482: not origin/HEAD first, then already cut (no check run), then the check.
- **G-06** (spec-0036). A lightweight or an annotated tag. FREE: every
  scenario reads a tag by its name. Recommendation: lightweight (it needs no
  identity).
  **The builder's choice: no grader sees it.**

### Fence and the paths stage

- **G-07** (spec-0296, spec-0297; the glossary's "Fence"). What a committed
  path is for check: HEAD's tree, or the index (so the pre-commit hook
  stops the commit that adds one); that `dhf` itself is classified through
  the paths directly under it; and that a project with no commit, or outside
  a repository, has none. Smallest: one glossary sentence. VISIBLE.
  Recommendation: the index (ED-07).
  **Stated by spec-43:** spec-0487 and the glossary's "Fence": the index; `dhf` classified through the paths under it; none outside a repository. Chosen differently from the release, which read HEAD's tree (a staged path passed).
- **G-08** (spec-0329, spec-0038, spec-0182). fence's result on standard
  output: spec-0329 says "its result names ratification/ratify as a removed
  component". Smallest: a contract scenario, e.g. `{:fence {:room ROOM :cut
  "spec-3" :scopes [...] :removed-components [...]}}`. VISIBLE.
  **Stated by spec-43:** spec-0483, `{:fence {:room ROOM :cut :commit :scope :kept :denied}}`, ROOM as given, `:scope` `:project` or a vector of scopes, `:slice` with `--slice` (spec-0488), `:removed` naming each component the cut's model no longer names. `:removed` is the spec's own key (the release's not probed).
- **G-09** (spec-0038, spec-0296). A cut holding a committed path neither
  denied nor kept: fence refuses it (exit 1, naming each, no room), or
  keeps it. Smallest: a row of spec-0295's kind. VISIBLE. Recommendation:
  refused, so nothing unclassified reaches a room.
  **Stated by spec-43:** spec-0484, refused, exit 1, naming each, no room. Chosen differently from the release, which kept the path.
- **G-10** (spec-0298, spec-0398, spec-0399). Under the slices layout, a
  changed event's publishers and subscribers: listed as `{:component ...}`
  scopes after the slices, unless a listed slice holds them, or folded into
  the slices that hold them. VISIBLE.
  **Stated by spec-43:** spec-0493: the publisher and subscribers a listed slice holds are not listed again; the others follow the slices as `{:component C}` scopes, in build order.
- **G-11** (spec-0295). Which kept files the bytes rule compares (only
  those outside the implementation, or the scope's kept implementation too)
  and whether an empty file counts: two empty files are the same bytes, and
  the same object (spec-0038). Smallest: "a kept file outside the
  implementation, not empty". VISIBLE.
  **Stated by spec-43:** spec-0485 and the glossary's "Fence": kept files outside the implementation, not empty.
- **G-12** (spec-0038). A ROOM that exists already, empty or not.
  Smallest: "a ROOM that exists and is not empty is refused, exit 2".
  VISIBLE.
  **Stated by spec-43:** spec-0486: a ROOM that exists and is not empty is refused, exit 2, left as it was; an empty one is used, as the release.
- **G-13** (spec-0183, spec-0226, spec-0397). `--slice` with `--context`
  or `--component`, and `--slice` without `:spec-layout :slices`; and
  spec-0176's usage of fence names no `--slice`. Smallest: rows of
  spec-0185 (exit 2), and the usage. VISIBLE; the usage is the spec owner's
  (slice 01's table, the oracle's `help` golden).
  **Stated by spec-43:** spec-0488: `--slice` joins `--context` and `--component`, its components added to theirs (as the release, not the planner's refusal: spec-0226 lists scopes the same way); without the slices layout exit 2, saying so (the release said only that the cut holds no such slice). spec-0176's usage gains `[--slice S]`.
- **G-14** (spec-0038). The room's commit: author, date, message, branch.
  FREE: no scenario reads them. Recommendation: an identity of rdm-spec's
  own, so a fence never fails where git has none configured.
  **The builder's choice: no grader sees it.**
- **G-15** (spec-0038). A submodule at the cut. FREE.
  **The builder's choice: no grader sees it.**

### Scopes

- **G-16** (spec-0237, spec-0238, spec-0298). scopes' result on standard
  output, and the shared file's line. Smallest: a contract scenario, e.g.
  `{:scopes {:from "spec-3" :to "spec-4" :scopes [{:component :kernel/bus}
  ...] :shared-files ["dhf/spec/glossary.md"]}}`, each shared file also a
  warning line, exit 0. VISIBLE.
  **Stated by spec-43:** spec-0489, `{:scopes {:from :to :scopes [...] :shared [...]}}` (the release's `:shared`), each shared file also a warning line, exit 0.
- **G-17** (spec-0238, spec-0340). Which changed files are shared: the
  model, the needs, the ledger (which every stamp changes), the
  configuration, the wiki, `docs/`; the event catalogue when no contract
  changed (spec-0340 says only "no scope"); a denied path (the
  implementation, the measurements). Smallest: "a shared file is a changed
  kept path no component owns; the event catalogue is read event by event
  and is never one". VISIBLE.
  **Stated by spec-43:** spec-0490 and spec-0238: a shared file is a changed path the clean room keeps that is no component's feature file or design prose and not the event catalogue (the model, the ledger, the needs, the configuration, the wiki, docs); a denied path is neither scope nor shared.
- **G-18** (spec-0298, spec-0348). Which cut's relationships name an
  event's subscribers when the event was added, removed or moved: each
  cut's own, together. VISIBLE.
  **Stated by spec-43:** spec-0491: each cut's own relationships, together (also slice 08's G-18 for the subscribers' side of an event).
- **G-19** (spec-0399). A touched slice all of whose components an earlier
  slice of the listing holds: listed with `:components []`, or left out.
  VISIBLE. Recommendation: listed.
  **Stated by spec-43:** spec-0494, listed with `:components []`.
- **G-20** (spec-0237). Two cuts that are not cuts, the same cut twice, or
  the later first. Smallest: "each is a cut, as fence's (exit 2); the
  change is read from the first to the second". VISIBLE.

### Verify
  **Stated by spec-43:** spec-0492: each must be a cut (exit 2 naming the one that is not); the same cut twice lists nothing, exit 0; the later first is read from the first to the second, `:from` the first, as the release.

- **G-21** (spec-0202, spec-0441, spec-0358). spec-0441's verify row gives
  no cycle.edn, so spec-0203's refusal answers before the declaration's if
  verify reads the cycle first, as spec-0202 says. Smallest: spec-0441's
  Given gains "and dhf/cycles/03/cycle.edn naming spec-3". VISIBLE.
  **Stated by spec-43:** spec-0441's Given gains "and dhf/cycles/03/cycle.edn naming the cut spec-3" (reworded, its id kept).
- **G-22** (spec-0207, spec-0033, spec-0202, spec-0391). Whether verify
  empties the cycle's results directory, and makes the checkout afresh,
  before it runs (an earlier run's results would pass a no-op test
  command), and makes `results/NAME` for a `:plugin` check before asking
  it. Smallest: "verify starts each run with an empty results directory and
  a fresh checkout". VISIBLE.
  **Stated by spec-43:** spec-0495 (an empty results directory and a fresh checkout, as the release) and spec-0496 (`results/NAME` made empty before the plugin is asked).
- **G-23** (spec-0207, spec-0410, spec-0404; `07-measure/scorer.feature`,
  spec-0173, spec-0417). The scenarios the tests check requires under each
  scope: the project, contexts and components (every scenario, as
  spec-0207 says?), `[{:spec S}]` (spec-0410), `[{:slice S}]` and
  cycle.edn's `:slice` (spec-0404). Smallest: one glossary sentence under
  "Check". VISIBLE.
  **Stated by spec-43:** spec-0498 and the glossary's "Check": the scenarios of what the scope reaches, in any slice; a result outside the scope neither required nor held. The release's reading for a context or component scope was not probed.
- **G-24** (spec-0361, spec-0363; slice 08's G-18). Which `:plugins` a
  `:plugin` check is asked from: the cut's configuration, read in the
  checkout, as every check is (spec-0205); and the Gate's and
  CycleVerified's subscribers: the project's root's, as every task's.
  Smallest: one sentence in the glossary's "Check". VISIBLE.
  **Stated by spec-43:** spec-0504 and the glossary's "Check": a `:plugin` check's plugin is the cut's configuration's, read in the checkout; the Gate plugins and CycleVerified subscribers the project root's. Also closes slice 08's G-18.
- **G-25** (spec-0207, spec-0062). A results file the reader cannot read:
  fails the tests check, naming it, or passed over. VISIBLE.
  Recommendation: fails, as it refuses a release (spec-0454).
  **Stated by spec-43:** spec-0499, fails the tests check, naming the file, as the release.
- **G-26** (spec-0207, spec-0208; the glossary's "Verified"). An outline:
  "named by at least one result, and every result naming it passed", or
  verified as status says (a passing result for every row). VISIBLE.
  **Stated by spec-43:** spec-0500: as status says, a passing result for every row; a row with none fails the check.
- **G-27** (spec-0204, spec-0370). A ROOM that is not a git repository; an
  implementation path the room does not hold (absent from the checkout too).
  Smallest: "the room's implementation is read as git would commit it in a
  repository at ROOM". VISIBLE.
  **Stated by spec-43:** spec-0497: the room read as git would commit it in a repository at ROOM, whether or not it is one; an implementation path the room does not hold is absent from the checkout.
- **G-28** (spec-0215, spec-0216, spec-0314). The record and the result of
  a refusal: the reason's key (`:reason`, a string?), whether `:room-tree`
  and `:checks` are in it, and standard output on exit 1 (`{:verify ...
  :outcome :refused}`?). Smallest: a contract row of spec-0216. VISIBLE.
  **Stated by spec-43:** spec-0501: on exit 1, `{:verify {:cycle :cut :outcome :refused :checks [...]}}`; ratified.edn with `:room-tree`, `:checkout` (when made), `:checks` and `:reason`, a string; a failed check carries `:reason` too. As the release.
- **G-29** (spec-0361, spec-0315). After a check refused (exit 2), whether
  the later checks are `:not-run`, as after one that failed. VISIBLE.
  Recommendation: they are.
  **Stated by spec-43:** spec-0503, the later checks `:not-run`.
- **G-30** (spec-0215). The room's tree hash H: the id of the git tree
  holding exactly the room's implementation as git would commit it, at its
  paths, or the room's whole tree. Smallest: one sentence in the glossary's
  "Verify". VISIBLE.
  **Stated by spec-43:** the glossary's "Verify": the git tree of exactly the room's implementation, at its paths, as git would commit it (the release gave the empty tree for an empty implementation).
- **G-31** (spec-0314, spec-0318, spec-0322). The keys of each check beyond
  those stated: `:serves`' `:seconds` and `:budget`, a failed check's, one
  not run. Smallest: one row per kind of spec-0314. VISIBLE.
  Recommendation: `{:name :kind :outcome :seconds}` and the kind's own
  keys; a check not run, `{:name :kind :outcome}`.
  **Stated by spec-43:** spec-0502 and spec-0501: every check `{:name :kind :outcome :seconds}`; `:tests` adds `:scenarios`, `:runs` `:budget :margin`, `:serves` `:budget :polls :answered-on-poll :margin :probe-seconds` (as the release), `:plugin` nothing; a failed check adds `:reason`; one not run is `{:name :kind :outcome :not-run}`.
- **G-32** (spec-0331, spec-0332, spec-0333; the glossary's "Allure
  mapping"). The Allure elements and the spec elements a mapping may name,
  and what each spec element is in a result (`:context` the context's id,
  `:component` its id without the colon, `:gherkin-step` keyword and text,
  an outline's step with its row's values). Smallest: the glossary lists
  them: exactly the default mapping's keys and values. VISIBLE.
  **Stated by spec-43:** the glossary's "Allure mapping": exactly the default mapping's keys and values, each spec element as the runner fills it (spec-0308): the context's id, the component's name, a step's keyword and text.
- **G-33** (spec-0197, spec-0319, spec-0321, spec-0322, spec-0331). Each
  failure's line: its file (test.log, ratified.edn's, the result's), rule
  and wording; test.log's content (standard output, then error stream).
  FREE: the rule is the component's (`dhf/spec/README.md`), and the
  scenarios say only what each names.
  **The builder's choice: no grader sees it.**
- **G-34** (spec-0318, spec-0341). A poll's window (until the next poll is
  due) and the request it sends. FREE.
  **The builder's choice: no grader sees it.**
- **G-35** (spec-0318). Where a served app's own output goes: never verify's
  standard output, which holds only its result. FREE.
  **The builder's choice: no grader sees it.**

VISIBLE: 29 (G-01 to G-05, G-07 to G-13, G-16 to G-32). FREE: 6 (G-06,
G-14, G-15, G-33 to G-35).

## Questions for the spec owner

- ED-01: cut runs check as a command through RDM_SPEC, and cut and fence are
  instantiated on their own ahead of slice 02 (three instance lines in
  `rdm-spec.wac`, not one).
- ED-04: the model gains "Reads cuts with the git helpers of" cut, from
  scopes and verify. **Done by spec-43 for scopes only:** the model's lint
  refuses verify to cut (a-contract-only: two bounded contexts relate only
  through events), so verify still reads the cut through the repository. The
  spec owner chooses: verify reads cuts with git itself (ED-04 B for verify),
  the helpers move to the kernel (ED-04 C), or the lint's rule changes.
- spec-0362, spec-0391: a `:plugin` check runs in the checkout but is given
  `:checkout` and `:results` relative to the project's root, which it
  cannot find from where it runs; absolute paths, or paths relative to the
  checkout, would serve it. **Settled by spec-43:** absolute paths (spec-0362
  and spec-0391 reworded, ids kept). CycleVerified's `:checkout` stays
  relative: its subscribers run in the project's root. The oracle's goldens
  of a `:Check` request, if any, move with it.
- G-13: fence's usage in spec-0176 names no `--slice`. **Settled by spec-43:**
  spec-0176's rows gain `[--slice S]` for fence, `[--spec NAME] [--slice S]`
  for status and release, and `[--spec NAME]` for trace, the options the
  spec gives them (slice 05's G-19 too). A behaviour change: the oracle's
  `help` golden must be re-recorded with it (`--changes` naming it).

## Settled after the spec gaps (spec-43)

- ED-04, half: the model's lint holds contexts to events, so verify cannot
  read cuts with regeneration/cut. Verify reaches git itself through
  rdm:host/process (accepted by the integrator under the spec owner's goal;
  the spec owner may choose the kernel or a lint change instead). Scopes
  reads cuts with cut, as the model now says.
- spec-0176's usages changed (fence --slice; status, release --spec and
  --slice; trace --spec): the oracle's help golden moves with them when the
  component build is installed, recorded with --changes.

