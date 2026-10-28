# rdm-spec: the way we work

As Löyly's `docs/wow/way-of-working.md` (loyly@17d68d2). How the method runs,
and how a cycle runs, for the integrator. The integrator runs the steps, never
plans, builds, reviews or grades, and reports time and tokens after every run.

## The method in one paragraph

The spec is the source of truth and code is a build product of it. What a
project knows is captured in sources (`dhf/raw/`) and distilled into a wiki
(`dhf/wiki/`) whose every claim cites one. Work is delivered in slices
(`dhf/spec/<NN-slice>/`), each planned, specified as Gherkin scenarios, broken
into tickets, built test-first, verified by a gate the builder does not
control, graded black box, and closed. Every divergence from a slice's plan is
a decision, kept in the slice. Because the spec, not any codebase, is the
asset, the implementation is deleted and regenerated, and the difference
between two generations measures how complete the spec is (loyly, section 0).

## The layers

Each layer is derived from the one above it, and nothing may drift from what
precedes it (loyly, section 1):

```
KNOWLEDGE        sources: dhf/raw/ (cited, never edited)
   |             distilled into dhf/wiki/, every claim footnoted
   v
PLANNING         a slice: dhf/spec/<NN-slice>/plan.md (status, goal,
   |             references, scope in and out)
   v
ACCEPTANCE       its scenarios: <NN-slice>/<component>.feature, stamped
   |
   v
TICKETS          <NN-slice>/ticket-NN-<slug>.md, each naming the scenarios
   |             it satisfies (written in the planning phase)
   v
IMPLEMENTATION   test-first: scenario red, unit red, green, refactor
   |             (src/, test/: replaced by the next regeneration)
   v
VERIFICATION     rdm-spec verify, then graders; what was decided goes into
                 <NN-slice>/decisions.md
```

The same domain names appear in every layer, re-cased only: a Gherkin line
and a function that disagree about a name are a bug by construction.

## No slice, no code

Behaviour changes start in a slice, never in an editor (loyly, section 2).
Before any implementation work: the slice exists with its `plan.md`, its
scenarios state the acceptance criteria, and its tickets break it into units,
each naming the scenarios it satisfies. Someone arriving with an idea and no
slice writes the slice first. `rdm-spec check` refuses a feature file outside
every slice, and a slice with no plan or no `decisions.md`.

## Every document cites another

A wiki page cites the source it distils; a plan cites the needs and pages it
rests on; a ticket cites the scenarios it satisfies; a decision cites the
plan item it diverged from (loyly, section 3). A document with no citation is
not finished. `rdm-spec check` holds the needs and the wiki to it; plans,
tickets and decisions are held by the planner and the reviewer.

## The firewall

The spec and the wiki never name a stack: no language, framework, database or
vendor (loyly, section 4; `rdm-spec check` against `:stack-words`). The stack
lives in `dhf/harness/stack.md`. When the spec is not enough to build from,
that is a spec bug: fix the slice, not the reading.

## The rules

- **Slice development** (ADR 0005): the slice is the unit of work. A cycle
  builds one slice, whole, with every component it reaches; its checklist,
  score and status are the slice's, regression items apart. A slice goes
  planned, ready, building, done, in its plan's `status:` line, and keeps what
  was decided in it in its `decisions.md`.
- **One builder per cycle.** Never contexts in parallel: a cycle's scopes are
  built one after another by one builder, in the order `scopes` lists them
  (spec owner, cycle 13; dhf/cycles/retrospective-01-12.md).
- **A whole-project cycle at least every fifth cycle.** A scoped cycle hides
  what it does not rebuild (dhf/cycles/retrospective-01-12.md, root cause 2);
  Löyly rebuilds the whole application every cycle. Scoping saves about two
  thirds of a cycle's cost, so it stays, and the whole-project cycle is the
  check on it (spec owner, spec-22).
- **Every run is observed** (dhf/spec/07-measure/observe.feature): its capability
  is proven by its result, and its defence is recorded beside it.
- **One grader at a time per item;** several graders may split a large
  checklist, each in its own room, since nothing of theirs is merged but
  their verdict files.
- **Agents run in the background:** stopping the integrator's own turn stops
  them too (cycle 17 lost a grader that way). Ask while they run; do not stop.

## The steps

1. **The slice:** plan it, or change its plan, in `dhf/spec/<NN-slice>/`;
   `rdm-spec stamp` (take ids from its output, never from memory),
   `rdm-spec views` if the model changed, `rdm-spec check`.
   **Plan phase** (a separate invocation, never the builder's): one planner
   (`docs/wow/harness.md`, "The planner") writes the slice's tickets and the
   decisions it expects to need; the spec owner accepts them or sends them
   back; then the plan says `status: ready`. Commit; tag the cut `spec-N`.
2. **The cycle:** `rdm-spec scopes spec-(N-1) spec-N` names the slices the
   change touched; pick the one to build (one per cycle; every slice, in
   order, for a whole-project cycle); judge the shared files it names; record
   the cycle in `dhf/cycles/NN/cycle.edn` (cut, `:from` = the cut of the
   installed release that judges it, `:slice`, builder, grader, spec owner);
   set the slice's plan to `status: building`.
3. **Fence** the room (`--slice S`); `rdm-spec observe begin NN builder ROOM`;
   brief one builder with `docs/wow/harness.md`, "The builder", naming the
   slice: its `plan.md` first, then its feature files.
4. **When it ends:** `observe end NN builder --tokens N`; run the suite twice
   (no fixture, no process, `/tmp` unchanged); run the held-still oracle on the
   build, scoped to the spec change (`RDM_SPEC=... bb dhf/harness/oracle/oracle.clj
   --from <:from> --to spec-N`): it judges only the components the change did
   not reach, so any difference is a regression; the in-scope ones are the
   graders'. `rdm-spec verify ROOM NN`
   with the installed release; `rdm-spec checklist NN`.
5. **Review** (every slice cycle, before grading): one reviewer
   (`docs/wow/harness.md`, "The reviewer") reads the build against the slice's
   plan, tickets and scenarios, hunting invented rules and drift from the
   plan. Its findings go to `proposed-spec-changes.md`, never to the builder;
   an invented rule the spec owner wants becomes a scenario in the slice.
6. **Package, then grade.** Package the build outside the room from its
   `src/` and the plugin templates (copy `plugins/` to `res/rdm-spec/plugins/`
   in a scratch directory, then `bb --classpath src:res uberjar rdm-spec.jar -m
   rdm-spec.kernel.command`; plain `bb uberjar` would take `bb.edn`'s
   `:paths`, `test` among them; cycle 21's jar, from `src/` alone, carried no
   template). The grading
   room has no implementation in it: `project/` (the cut's `dhf/` and
   `docs/`, without `src/`, `test/` or the oracle), `bin/rdm-spec` running
   that jar (`bb JAR`), the sources;
   `observe begin NN grader`; brief with `docs/wow/harness.md`, "The grader".
7. **Record:** `rdm-spec score NN --verdicts A --verdicts B` (one file per grader);
   `rdm-spec scoreboard (NN-1) NN`; `observe report`; the posterior
   (`dhf/cycles/analysis/posterior.py`); `labels.yaml` for each fail you looked
   at; `decisions.yaml` from the builder's report; `proposed-spec-changes.md`.
   The score's `:slice` is the slice's own items, `:regression` the rest.
8. **Bring the build in** (src, test, runner) and run its suite in the
   repository; commit.
9. **The spec owner decides:** accepted, the build is installed as the release
   (an artifact in the release layout, the wrapper pointed at it), and
   recorded as `:reviewed` in `cycle.edn`. Then the in-scope goldens move to
   the new release: `oracle.clj record --reason "spec-N" --changes C:K,...
   --from <:from> --to spec-N`, which refuses any golden outside the scopes.
   The decisions accepted go into the slice's `decisions.md`; when every one
   of its scenarios is verified, the slice's plan says `status: done`.

## Re-recording a golden

A golden that moves is an alarm, so each move is named and given its reason
(loyly:harness/README.md, "The goldens, and the three reasons one can
change"). Three events look alike in a diff and are never confused:

- **The instrument's behaviour changed.** It needs a scenario that states the
  new behaviour; re-recording to get a green is never a reason.
- **The wording changed,** and nothing else: no exit code, no structure. The
  oracle keeps only a finding's head, so wording alone moves no golden.
- **The spec grew.** A fixture's checklist gains items. The test is that the
  golden's diff is additions only: no existing item's id, name or rule moves.
  A re-record that changes an existing item is not this case, whatever its
  reason says.

`--changes` names every existing golden a record changes, and the reason
names the scenario that states the new behaviour.

## When the spec changes the judge's own contract

verify is judged by the installed release. When a spec change alters what
verify itself holds a build to (cycle 25: spec-0403, the tag labels), the
installed release refuses every build of the new contract, and the oracle's
goldens hold the old reading. Then (spec owner, cycle 25):

1. Run verify with the installed release anyway, and keep its record
   (`ratified-by-cycleNN.edn`): every finding must be the changed contract's,
   and none other.
2. Grade the build black box, as always.
3. The spec owner decides on that evidence. Accepted: install the build,
   record the goldens the contract moved (`--changes`, reason naming the
   scenario that states the new contract), verify the cycle again with the
   new release, then score it.

## Mistakes already made, not to repeat

- Ids copied into a commit message from memory instead of the stamper.
- A config edit that dropped a closing brace (run `check` before committing).
- A scratch clone left behind with a trial change in it.
- A golden recorded by a build nobody accepted.
- A release installed without moving its in-scope goldens (cycle 23): the
  next fence was stopped by its own gate.
- A diagnosis read from a tool's output against the integrator's own
  expectation, not the spec: spec-0416 was written for a "bug" spec-0398
  states as the rule (cycle 25).
