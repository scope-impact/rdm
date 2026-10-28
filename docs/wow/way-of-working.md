# rdm-spec: the way we work

As Löyly's `docs/wow/way-of-working.md` (loyly@17d68d2). How a cycle runs, for the integrator. The integrator runs the
steps, never builds or grades, and reports time and tokens after every run.

## The rules

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

1. **Spec change** in `dhf/spec/`, `rdm-spec stamp` (take ids from its output,
   never from memory), `rdm-spec views` if the model changed, `rdm-spec check`;
   commit; tag the cut `spec-N`.
2. **Scopes:** `rdm-spec scopes spec-(N-1) spec-N`; judge the shared files it
   names; record the cycle in `dhf/cycles/NN/cycle.edn` (cut, `:from` = the
   cut of the installed release that judges it, scope, builder, grader, spec
   owner, model).
3. **Fence** the room; `rdm-spec observe begin NN builder ROOM`; brief one
   builder with `docs/wow/harness.md`, "The builder" and the scopes.
4. **When it ends:** `observe end NN builder --tokens N`; run the suite twice
   (no fixture, no process, `/tmp` unchanged); run the held-still oracle on the
   build, scoped to the spec change (`RDM_SPEC=... bb dhf/harness/oracle/oracle.clj
   --from <:from> --to spec-N`): it judges only the components the change did
   not reach, so any difference is a regression; the in-scope ones are the
   graders'. `rdm-spec verify ROOM NN`
   with the installed release; `rdm-spec checklist NN`.
5. **Package, then grade.** Package the build outside the room from its
   `src/` and the plugin templates (copy `plugins/` to `res/rdm-spec/plugins/`
   in a scratch directory, then `bb --classpath src:res uberjar rdm-spec.jar -m
   rdm-spec.kernel.command`; plain `bb uberjar` would take `bb.edn`'s
   `:paths`, `test` among them; cycle 21's jar, from `src/` alone, carried no
   template). The grading
   room has no implementation in it: `project/` (the cut's `dhf/` and
   `docs/`, without `src/`, `test/` or the oracle), `bin/rdm-spec` running
   that jar (`bb JAR`), the sources;
   `observe begin NN grader`; brief with `docs/wow/harness.md`, "The grader".
6. **Record:** `rdm-spec score NN --verdicts A --verdicts B` (one file per grader);
   `rdm-spec scoreboard (NN-1) NN`; `observe report`; the posterior
   (`dhf/cycles/analysis/posterior.py`); `labels.yaml` for each fail you looked
   at; `decisions.yaml` from the builder's report; `proposed-spec-changes.md`.
7. **Bring the build in** (src, test, runner) and run its suite in the
   repository; commit.
8. **The spec owner decides:** accepted, the build is installed as the release
   (an artifact in the release layout, the wrapper pointed at it), and
   recorded as `:reviewed` in `cycle.edn`. Then the in-scope goldens move to
   the new release: `oracle.clj record --reason "spec-N" --changes C:K,...
   --from <:from> --to spec-N`, which refuses any golden outside the scopes.

## Mistakes already made, not to repeat

- Ids copied into a commit message from memory instead of the stamper.
- A config edit that dropped a closing brace (run `check` before committing).
- A scratch clone left behind with a trial change in it.
- A golden recorded by a build nobody accepted.
