# Cycle 04: score and proposed spec changes

Cycle 04 added an oracle for the instrument (Löyly's oracle.sh). It was cut as
spec-6 and restarted on spec-7 when the spec owner replaced the
InstrumentJudged event with a gate: the command runs the oracle first and
stops fence and ratify on its findings.

1. **Fence:** `rdm-spec fence` built five rooms at spec-7: measurement,
   publishing, regeneration/fence, ratification and kernel/command.
   - spec/check and knowledge/sources were carried from their spec-6 rooms,
     because their features did not change.
   - regeneration/cut was missed in the re-fence. Its spec-6 build no longer
     fit the spec-7 fence (fence calls cut's helpers; the cut steps needed
     another support file). So cut was rebuilt by a builder in a room holding
     the merged build minus cut.
2. **Merge:** three integration fixes by hand, all where scopes meet (below).
   The suite passed: 226 of 226 scenario results and 167 unit tests.
3. **Ratify:** `rdm-spec ratify` passed against spec-7, with 191 scenarios.
4. **Checklist:** 102 items, the cycle's scopes only.
5. **Grading:** three graders, none of them a builder, each in its own
   grading room holding only a copy of the ratified build, the source
   checkouts and a wrapper for the build. The oracle's grader built its own
   fixture oracles from the instrument's outputs and broke one golden.
6. **Score:** **102 of 102** pass.
7. **Scoreboard 03 → 04:**
   - 68 items compared: **1 better (spec-0098), 0 worse**
   - 34 new items, all passing
   - the denominator moved from 105 to 102, and that was said out loud
   - 37 items out of scope, counted apart

## Integration fixes by hand

1. **Publishing's step for spec-0096** filled `DIR` through the kernel's
   When; the rebuilt kernel fills `DIR` only from `--sources`. The step now
   fills its own results directory.
2. **The kernel's Given "the configured results directory does not exist"**
   again built a project holding only a configuration, so publish had no
   spec. It is now a copy of the room. This is the same seam as cycle 01:
   the spec never pinned the Given's project.
3. **regeneration/cut was rebuilt** against this cycle's fence: inside one
   context, fence depends on cut's helper functions.

## Decided by Löyly (spec owner's rule, ADR 0002): write these in

The rule arrived mid-cycle, after spec-7, so no cycle 04 builder or grader
had the wiki page. These are the open choices it settles:

1. **Ratify's checkout:** a fresh export of the cut with no history, made
   with `git archive`, as Löyly's fence warns that a plain-path clone shares
   the object store and ignores depth. Three builders used three methods.
2. **The test command's output:** the gate trusts its exit code, not its
   prose (Löyly), so the output is kept for a person in
   `dhf/cycles/NN/test.log`, named in a refusal. Add it to the cycle layout.
3. **One copy of the model hash.** Löyly's `hjson` is "one copy of the byte
   contract, because four copies drift into four instruments". The site keeps
   its own copy beside the views'; the spec should say the site reads a
   view's freshness through the architecture context's check, or the
   kernel's shared code holds the one hash.
4. **spec-0215:** drop the literal empty-tree hash. The Given gives the
   room's tree hash as computed, and the Then expects it in full. Four ratify
   builders worked around the contradiction.

## A. Wrong or contradictory

5. **spec-0234's Given** ("the ledger has issued ids up to 0227") also
   flags the real spec's ids 0228 and above in a copy of the room. Say "a
   ledger below a scenario's id".
6. **spec-0202 vs the gate:** "Ratify reads the cycle before anything else",
   but the oracle gate now runs first. Say "before anything of its own".
7. **spec-0093 "its description"** could be the model's or the feature
   file's description line.

## B. Contracts no scenario states

8. **Shared helpers inside a context are a contract.** Fence calls cut's
   `rev`, `cuts-at`, `checkout!` and `repository!`. Either the model shows
   fence → cut, or each component keeps its own git helpers.
9. **The kernel's shared Givens drift.** "The configured results directory
   does not exist" changed meaning twice. Pin what project each shared Given
   leaves.
10. **The checklist's context and component** come from the model's names,
    while spec-0210 shows ids. Say which.
11. **Scoreboard warnings** lack `FILE:LINE`.
12. **A missing oracle namespace:** fence and ratify skip the gate with a
    warning. Löyly's gate fails closed when its check is missing. Decide;
    Löyly suggests refuse.

## D. Process

- **The integrator missed cut in the re-fence.** A scope list should come
  from the spec diff between cuts, not from memory: which component's
  feature file, or which shared contract, changed. Proposal: a small task,
  `rdm-spec scopes CUT CUT`, that lists the components whose features
  changed.
- **Graders in their own rooms worked.** No grader read outside its room this
  cycle. One wrote helper scripts to the session scratchpad first, then
  moved them.
- **Builders still write `dhf/allure-results/`** when run without
  RESULTS_DIR (two this cycle, removed). The test command could refuse to run
  without it.
