# Cycle 17: score and proposed spec changes

spec-21: the flat oracle, the Allure mapping, cycle 16's fixes. Cycle 16's
release ran it. It is the first cycle gated by verify rather than ratify.

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (7 scopes) | 44.3 | 467,749 | 391 of 391 scenario results and 124 unit tests, twice | 0 |
| grader, first run | 44.9 recorded (about 8 of work, then idle) | 155,279 | cancelled with the integrator's turn; no verdicts | 0 |
| grader, second run (90 items) | 14.9 | 243,718 | 90 pass | 0 |
| **cycle** | **104.1** | **866,746** | | **0** |

The cancelled grader cost about 155,000 tokens for nothing. The second
grader wrote its verdicts as it went (`verdicts.partial.edn`), so an
interruption would no longer lose them.

1. **Scopes:** seven, as cycle 16's release listed them.
2. **Integrator's checks:**
   - Suite: 391 of 391, twice; `/tmp` unchanged; no process left.
   - `check` exits 0.
   - The new `scopes` lists 3 for spec-17 → spec-18.
   - The new oracle reads a flat copy of the artifact. The migration was
     dry-run twice with 0 differences: 8 goldens reshaped, each value
     unchanged, and 13 new cases recorded.
3. **Verify:** passed, by cycle 16's release: tests (287 scenarios), then
   `answers`.
4. **Score:** **90 of 90.**
5. **Scoreboard 16 → 17:**
   - 67 compared: **2 better (spec-0062, spec-0298), 0 worse**
   - 23 new items

The builder's 18 decisions are in `decisions.yaml`. spec-22 already settles
four of them:
- the order of goldens
- spec-0230 against spec-0327
- the golden's shape
- "answers"

## For spec-23

1. **spec-0153 and spec-0310 against spec-0323.** The first two say a second
   step that "throws" fails the result; spec-0323 says only a Then whose
   assertion does not hold is failed, and anything else is broken. Reword
   0153 and 0310 so that the Then's assertion fails.
2. **spec-0325's order:** spec-22 now orders goldens by component id. This
   build uses a fixed list, so the next build of the oracle changes it.
3. **spec-0320's "starts nothing":** the tests check has already run, so
   "nothing" means no app of a `:serves` check. Say so.
4. **spec-0310's message:** the build wraps the error with the step name and
   line. Say the statusDetails message *holds* the error's text.
5. **spec-0152's "naming its row":** the rows share a `name`; `parameters`
   and `historyId` tell them apart. Say which.
6. **spec-0314's N:** state whether a planned (`@wip`) scenario's result
   counts as named.
7. **Persistent builder decisions** (decided in both cycles 16 and 17;
   `decisions.yaml`):
   - **spec-0299:** ties in the build order
   - **spec-0309:** an outline row's historyId. Cycle 16 wrote
     `spec-0013[plan, 3]`, cycle 17 `id|values`.

   Pin both.
8. **Minor:** a step's `attachments` is sometimes `null` rather than `[]`.
