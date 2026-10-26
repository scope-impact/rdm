# Cycle 16: score and proposed spec changes

spec-20: `ratify` became `verify`, abstract and declared per project
(`:tests`, `:serves` and `:runs` checks). Results are written in Allure's own
model, from the Gherkin scenario. Cycle 15's release ran this cycle.

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (9 scopes, ratify removed) | 41.6 | 506,027 | 357 of 357 scenario results and 117 unit tests, twice; each scope seen failing first | 0 |
| grader (98 items) | 11.4 | 198,208 | 96 pass, 2 fail | 0 |
| **cycle** | **53.0** | **704,235** | | **0** |

The builder says its first unit-test run briefly made and removed a
directory in /tmp. Observation saw nothing left behind, which is all it can
see.

1. **Scopes:** the release listed ten: nine components, plus
   `ratification/ratify`, which spec-20 removed. `fence` refused that one
   (it names no component of the model), so the integrator fenced the nine
   and removed ratify's code from the room by hand.
2. **Integrator's checks:**
   - Suite: 357 of 357, twice; `/tmp` unchanged; no process left.
   - Oracle: 0 differences, and `check` exits 0.
   - The new `verify`, run on a clone of this repository, passes both of
     rdm-spec's own checks: tests (276 scenarios), and `answers` with 29.96
     of its 30 seconds left. The record holds `:checks`.
3. **Ratify** (the last time by that name): passed against spec-20, run by
   cycle 15's release with `:test-command`.
4. **Score:** **96 of 98.**
5. **Scoreboard 15 → 16:**
   - 34 compared: **1 better (spec-0307), 1 worse (spec-0298)**
   - 64 new items
   - 4 out of scope

## The fails

- **spec-0298, a regression.** A change to a nested key of SpecParsed's
  `:needs`, whose `:read-by` is a flat list, reached all five subscribers.
  It should reach only the two the list names.
  - Cycle 15's `scopes` handled this (`(sequential? v) (set v)`).
  - Cycle 16's rebuilt `readers` looks the key up by its full path, misses
    a flat list, and falls back to every subscriber.
  - It errs on the safe side (more scopes, never fewer), but nothing in the
    spec kept it from regressing.
- **spec-0062.** An unreadable result is named on two lines, because the
  JSON parser's message carries a newline. The README's "one line per
  finding" rule was broken by a relayed message.

## For spec-21

1. **Pin the flat `:read-by` list for a key holding maps**, as a row of
   spec-0298: a nested key's change reaches the readers the flat list names.
   This is the case the regression broke.
2. **A relayed message is one line:** any newline in a message that rdm-spec
   relays is replaced (spec-0062; README, "How every task reports").
3. **Fence removes a component the cut deleted.** `fence` accepts a
   component that `scopes` lists as removed, and removes its code by the
   old cut's model.
4. **Pin the oracle's own result exactly.** This build changed it unnoticed:
   `:differences` became `:differs`, and `:compared` turned from pairs into
   maps. That is the unpinned-output class of cycles 08 and 11, and it is
   the last of the retrospective's list still loose.
5. **Builder decisions to pin:**
   - A `:serves` app answers on any HTTP response to `GET /`.
   - It is polled every budget ÷ polls seconds; a `:serves` margin is in
     polls, a `:runs` margin in seconds.
   - verify checks every command and port before running any check; a
     cannot-run writes no `ratified.edn`.
   - An outline row's historyId is `spec-0013[plan, 3]`.
   - The tests check does not demand a result per outline row.
6. **Allure's `unknown` status** (spec-0068 counts it, spec-0313 lists four
   valid statuses). The builder reads it as not passed; state it.
7. **Grader's notes:**
   - spec-0310's statusDetails wraps the message; say "holds".
   - spec-0200 needs namespaced component ids; say so, or warn on bare
     ones.
   - spec-0317: a command not found should start nothing.
8. **Scenario ids in the cut's commit message were wrong:** the stamper
   numbers in file order. The spec is right; the message is not. Use
   `rdm-spec stamp`'s own output when writing commit messages.
