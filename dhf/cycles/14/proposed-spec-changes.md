# Cycle 14: score and proposed spec changes

spec-18: the read-by rule and cycle 13's findings. The first cycle fenced by
the deny-list (`:clean-room :deny`, `:keep`) and the first whose every agent
run was observed by `rdm-spec observe` itself.

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (4 scopes) | 22.8 | 391,417 | 315 of 315 scenario results and 124 unit tests, twice; each scope seen failing first | 0 |
| grader (31 items) | 5.6 | 122,516 | 31 pass | 0 |
| **cycle** | **28.4** | **513,933** | | **0** |

Cycle 13 took 69.0 minutes and 951,569 tokens for 12 scopes.

The integrator's own run is not observed. Reads are not observed.

1. **Scopes:** four in all, in build order. Three are the components whose
   feature files changed. The fourth, kernel/command, was reached by the
   README's fixture rule and the glossary's gate rule. The installed
   `scopes` (spec-17's rule) listed 12, because it read the added `:read-by`
   as a change to every event. The four were applied by hand, following
   spec-18's own rule.
2. **Fence:** the room held only the kept paths. It had no `dhf/cycles`, and
   exactly the four scopes' code was removed.
3. **Integrator's checks:**
   - Suite: 315 of 315, twice, with no fixture left (`/tmp` 7612 → 7612).
   - Oracle: 0 differences.
   - The new build's `check` on this repository exits 0.
4. **Ratify:** passed against spec-18, run by cycle 13's release.
5. **Score:** **31 of 31.**
6. **Scoreboard 13 → 14:**
   - 28 compared: **0 better, 0 worse**
   - 3 new items (spec-0300, 0301, 0302)
   - 79 out of scope, said out loud

## For spec-19

1. **The first appearance of `:read-by`** (integrator; it contradicts
   spec-0301 in practice). The new build's `scopes spec-17 spec-18` lists
   8 scopes, although only `:read-by` was added. That listing reads the
   first appearance of a per-scenario-key map as every sub-key being added.
   spec-0301's fixture changes an existing `:read-by` but never introduces
   one. The spec owner should decide two cases:
   - when `:read-by` first appears
   - when a key's readers are unknown at the earlier cut

   Proposed: `:read-by` is metadata and reaches no subscriber, with a row
   that introduces it.
2. **The publisher when only `:read-by` changes** (grader). spec-0301 says
   "no subscriber" but leaves the publisher unstated. The build lists
   nothing.
3. **Status's result has no contract** (builder; the README asks for one).
   The build prints `{:results :scenarios :counts :unknown-results}`; pin it
   exactly.
4. **"No result" scenarios need another result present** (grader). spec-0064,
   0070 and 0302 do not say so. A results directory with no results at all
   exits 2 (spec-0061).
5. **Order within a scope list** (grader). spec-0298 and 0300 do not say
   whether the publisher comes first. spec-0237 states no order. The build
   follows the kernel-first and publishers-first rule of spec-0299; say so
   in those scenarios.
6. **Builder's readings to pin:**
   - A non-passing result labelled with the outline's id fails the outline,
     whatever its row.
   - A change to a key's shape string with the same sub-keys reaches every
     reader of that key.
   - A new or removed event reaches its publisher and all its readers.
   - An unexpected exception is `cannot-run`.
7. **spec-0086:** "a git repository holding exactly" does not say whether
   the files are committed. The build leaves them uncommitted.
8. **spec-0241:** it is unclear whether adding `views.md` counts as a change.
   The build's spec has no `architecture/views.md`.
9. **Minor:**
   - Shared-file warnings (`ids.edn`, `model.edn`) are noisy.
   - Cycle 13's minors still stand: svg reported as its mmd, the lint
     message's repeated name, the double heading, HTML determinism.

## Runs

- **The grader ran the installed `rdm-spec` once.** The pre-commit hook of a
  fixture made by `init` runs `exec rdm-spec check` from PATH. No verdict
  rests on that run. The skeleton's hook reaches whatever `rdm-spec` is on
  PATH: the README's fixture rule should say a fixture disables the
  skeleton's hooks, and `observe` cannot see this.
- **Grading took 5.6 minutes for 31 items.** The verdicts cite the commands
  run and their exit codes. A grader this fast is worth a spot check by the
  spec owner.
