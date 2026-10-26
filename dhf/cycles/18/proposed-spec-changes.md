# Cycle 18: score and proposed spec changes

spec-22: simpler scoping without `:read-by`, the domain model, a project's
contexts relating by call, and cycle 17's findings. Cycle 17's release ran
this cycle, with the flat oracle pinned.

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (6 scopes) | 43.5 | 501,300 | 401 of 401 scenario results and 128 unit tests, twice | 0 |
| grader (72 items) | 17.6 | 243,275 | 72 pass | 0 |
| **cycle** | **61.1** | **744,575** | | **0** |

**Writes outside the rooms:**
- **The builder** declared one: the tool harness logged a background run under
  the existing `/tmp/claude-0`. `observe` saw nothing.
- **The grader** briefly ran `git init` and a commit in its room's own root,
  after a missing tool (rsync) broke a command chain. It deleted the `.git`
  at once, and nothing left the room.

1. **Scopes:** six, as cycle 17's release listed them.
2. **Integrator's checks:**
   - Suite: 401 of 401, twice; `/tmp` unchanged; no process left.
   - The new build's `check` on this repository exits 0.
3. **Verify:** passed, by cycle 17's release: tests (290 scenarios), then
   `answers`.
4. **Score:** **72 of 72**.
5. **Scoreboard 17 → 18:**
   - 43 compared: **0 better, 0 worse**
   - 29 new items
6. **Posterior:** P(some item of the slice is wrong) = **2.6%** (90%
   interval 0.1–8.4%). This is the first cycle under the 5% bar.
7. **Builder decisions:** 19, in `decisions.yaml`. Two scenarios were decided
   again, a third time:
   - **spec-0299:** ties in the build order
   - **spec-0318:** the `:serves` margin

## The oracle's goldens do not survive a rebuild of the oracle

The new build's oracle finds **14 differences** in the pinned artifact:

- **Two are spec-22's intended change.** `scopes` now reaches every
  subscriber (`first-read-by`, `flat-read-by`).
- **Twelve come from how each case is run.** That covers the fixture's
  configuration (verify adds no `:verify` check), the room path, the results
  directory, and which files a fixture holds. spec-21 never stated any of
  it, so each rebuild of the oracle decides it again, and its goldens hold
  only for the build that recorded them.

Löyly avoids this by holding its harness still. rdm-spec regenerates its
oracle, so the case itself must leave nothing to decide.

## For spec-23

1. **Each oracle case carries its complete fixture:**
   `{:files {path text} :argv ["verify" "room" "00"] :env {...}}`. The oracle
   writes the files into a new directory, commits them with the fixed
   author and date when `:git true`, runs the argv, and records
   `{:exit :out :err :wrote}`. No setup is left to a build. The shared
   `:project`, `:cycles`, `:results` and `:cuts` become each case's own
   `:files`.
2. **Only a change to what an event carries reaches its subscribers.**
   Removing a key that is not part of an event's contract (as `:read-by`
   was) reaches none. Spec-21 → spec-22 listed 15 scopes for that reason.
3. **Pin the persistent decisions:** spec-0299 (build-order ties: the model's
   order) and spec-0318 (the `:serves` margin in polls).
4. **spec-0320's "starts nothing"** means no `:serves` app; the tests check
   has already run. spec-0317 has the same ordering.
5. **The `:serves` port check:** a port held only by connections in
   TIME_WAIT, after the app has gone, is free. Today verify refuses for about
   60 seconds after a run on the same port.
6. **The `component` label is the component's name** (the default mapping's
   `:component`). Say so. An evidence attachment's file should exist.
7. **Carried from cycle 17:**
   - spec-0153 and spec-0310 against spec-0323 (failed or broken)
   - the statusDetails message *holds* the error
   - spec-0152's row name
   - spec-0314's N with `@wip`
   - spec-0309's historyId
