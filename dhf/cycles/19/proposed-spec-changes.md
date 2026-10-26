# Cycle 19: score and proposed spec changes

spec-23: the oracle held still (a command the project keeps, run as the gate
before fence and verify), and cycle 18's findings. Cycle 17's release ran this
cycle; the held-still oracle judged it first.

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder | 36.2 | 464,050 | 385 of 385 scenario results and 124 unit tests, twice | 0 |
| grader (78 items, black box) | 14.9 | 218,769 | 77 pass, 1 fail | 0 |
| **cycle** | **51.1** | **682,819** | | **0** |

1. **Held-still oracle:** 15 differences against cycle 17's goldens, every one
   intended by spec-22 and spec-23.
2. **Verify:** passed (tests, 285 scenarios; then `answers`).
3. **Score:** **77 of 78**. The grader worked only from commands, exit codes,
   output and written files, and says the black box was enough for every item.
4. **Scoreboard 18 → 19:** 56 compared, 0 better, 1 worse (spec-0298).
5. **Posterior:** P(some item of the slice is wrong) = **55.9%** (90%
   interval 6.0% to 97.3%), over the 5% bar, because of the one fail.

## The fail

- **spec-0298 (wording):** with an event's relationships listed audit first,
  `scopes` listed the subscribers in the model's order, not relationship
  order. spec-0299 says components with no order between them come in the
  model's order, which allows the build's reading. **Proposed:** say once,
  in spec-0298, that subscribers come in the order the model's relationships
  name them, and that spec-0299's model order applies only between scopes no
  relationship orders.

## What the scenarios leave unstated (from the grader)

1. **The runner's step API:** `stack.md` names only `defstep`; the grader
   found `attach!` by introspection to grade spec-0311. Name `attach!` and
   what counts as an assertion (failed) against any other error (broken).
2. **The `:evidence` shape:** state `{level [attachment-name]}`, and whether
   an attachment on a step counts as one on the result (the build says yes).
3. **`RDM_SPEC`:** say it is a command line, and that it honours the caller's
   `TMPDIR` (the build fixes `-Djava.io.tmpdir`).
4. **A refused verify:** say whether a `:serves` port refusal writes
   `ratified.edn`, and that `:checkout` names a build only when one was made.
5. **Poll timing** behind `:answered-on-poll`: state it (cycle 19's builder:
   poll i at i × seconds/polls).
6. **A subdirectory:** say whether `rdm-spec` run in a subdirectory of a
   project finds the project (the build refuses, exit 2).

## For the way of working

- The grading room had no `docs/wow/harness.md`; the grader followed the
  brief alone. The grade room must carry `docs/wow/` (it is in `:keep`
  since this cycle).

## Installing cycle 19

Installed as release 13832de (OCI tag cycle-19). The held-still oracle first
gave it 15 differences, of which 5 were goldens holding what no scenario
states (cycle 17's wording after `RULE:`, stdout's `:reason`, check's
`:lint :findings`). `oracle.clj` now compares only error heads and skips the
paths in its `unstated` table; the 6 cases spec-22 and spec-23 changed were
re-recorded with `--changes`. Cycle 19's release: 0 differences.
