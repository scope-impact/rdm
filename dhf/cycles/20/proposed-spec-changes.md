# Cycle 20: score and proposed spec changes

spec-24, the whole project rebuilt from nothing in Clojure: the baseline that
cycle 21 (TypeScript) is compared with. Run by cycle 19's release (13832de),
whose gate is the held-still oracle. The build was packaged as a jar from
`src/` only, and three graders graded the jar as a black box.

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (whole project) | 71.9 | 632,948 | 386 of 386 scenario results and 96 unit tests, twice | 0 |
| graders A, B, C in parallel (286 items) | 15.8 | 602,302 | 284 pass, 2 fail | 0 |
| **cycle** | **87.7** | **1,235,250** | | **0** |

1. **Integrator's checks:** suite 386 of 386, twice; `/tmp` unchanged; no
   process left; the room's `dhf/` unchanged.
2. **Verify:** passed, by cycle 19's release (tests 286 scenarios, then
   `answers`).
3. **Score:** **284 of 286**. Grader A 94/95, B 96/97, C 94/94; each says the
   black box was enough for every item.
4. **Scoreboard 19 → 20:** 78 compared, 1 better (spec-0298, settled by
   spec-24), 1 worse (spec-0344); 208 new items.
5. **Posterior:** P(some item of the slice is wrong) = **79.6%** (90%
   interval 35.3% to 99.9%). A slice of 286 items with 2 fails, one real.

## The fails

- **spec-0344 (real):** run from the jar, the build sets `RDM_SPEC` to
  `bb -cp file:...rdm-spec.jar! -m rdm-spec.kernel.command`, which does not
  start. It derives the command line from a source classpath. Only grading
  the packaged build could show it. **Proposed:** say in spec-0344 that
  `RDM_SPEC` starts the same rdm-spec however it was installed (from sources
  or a package), and that the oracle command can run `$RDM_SPEC help`.
- **spec-0086 (wording):** `cut.yml` runs on pushes to `main`, while init's
  repository is on git's local default branch. **Proposed:** init names the
  branch it creates (`main`), and the workflow runs on that branch.

## The held-still oracle on this build: 25 differences

- **4 are spec-0331's two readings.** Cycle 19 read FILE in `FILE:LINE: RULE:
  what (the result's file)` as the result's file; cycle 20 reads it as the
  feature file, the result's file in the brackets. Grader B passed all 6 rows.
  **Proposed:** state which file FILE is.
- **21 are names and shapes no scenario states:** `check`'s rule names
  (`scenario-no-id` against `scenario-unstamped`, `id-not-issued` against
  `id-unissued`, `scenario-levels` against `level-multiple`,
  `spec-stack-word` against `stack-word`, `view-missing` against
  `view-stale`), the standard output of `score` (`:ungraded`), `fence`
  (`:files`, `:removed`, `:scope`) and `check` (`:knowledge`, `:spec`,
  `:views`), and status's warning for an unknown result. Cycles 17 and 19
  grew from the same code and agreed by inheritance; a build from nothing is
  the first to show the goldens hold more than the spec states. **Proposed:**
  either the spec states them (rule names for every finding, the standard
  output of every task, as spec-0314 does for verify) or the oracle stops
  comparing them. The spec owner decides.

## What the scenarios leave unstated (from the graders)

1. **Lines a finding names:** relationship findings name the container's
   line (spec-0003, spec-0015); citation-unchecked names the first line the
   citation appears (spec-0126); a scenario's line is the `Scenario:` line
   (status, trace, release); an unclosed map mid-file (spec-0072).
2. **Statuses:** whether `unknown` is an Allure status (spec-0313 against
   spec-0068); whether planned needs no results (spec-0097); status's exit on
   an unreadable result (spec-0313).
3. **The component label** holds the component's name, not its id.
4. **Refusals:** what still runs after a gate refusal (spec-0317, spec-0320:
   the tests check runs first); a scope naming nothing the model knows
   (spec-0200); tags not named spec-N (spec-0299, spec-0340); an empty
   feature file (spec-0156); a changed id prefix restamps (spec-0058).
5. **Output:** the oracle gate's standard output (spec-0232); fence's
   `:commit` and `:room` (spec-0038); absolute paths in findings
   (spec-0170, adopt).
6. **Observe:** results already in ROOM before a run (spec-0290).
7. **Audit:** whether check's clause-unknown warning is relayed (spec-0247);
   a tag closing mid-line keeps the space after it.
8. **The hook** calls the `rdm-spec` on PATH (spec-0025).

## For the way of working

- One `observe` run covers the three graders: observe allows one run per
  role at a time, so the run was begun on grade-a, and the graders' tokens
  are summed. Writes into the other grading rooms are not new entries of a
  watched directory, so a breach anywhere was still seen.
