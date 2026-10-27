# Cycle 22: score and proposed spec changes

spec-26: a pin dropped and restored is not dropped, every package carries the
plugin templates, the probe's url and limit. Scoped to knowledge/sources,
kernel/plugins and verification/verify, on cycle 21's code. Run by cycle 19's
release (13832de, cut spec-23).

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (3 scopes) | 27.8 | 315,448 | 404 of 404 scenario results and 91 unit tests, twice | 0 |
| graders A, B in parallel (65 items, the jar) | 14.3 | 325,805 | 63 pass, 2 fail | 0 |
| **cycle** | **42.1** | **641,253** | | **0** |

1. **Held-still oracle, scoped** (`--from spec-23 --to spec-26`): one
   difference, check's standard output now carrying `:sources`, which no
   scenario states; left unstated in the oracle (cycle 22), then 0 differences.
2. **Verify:** passed, by cycle 19's release (tests 300 scenarios, then
   `answers`).
3. **The install tests cycle 21 failed:** with this build, `rdm-spec check` on
   this repository exits 0 (no `source-pin-dropped`), and `plugin add` from the
   packaged jar lists and copies the templates.
4. **Score:** **63 of 65**. Grader A (Sources, Verify) 33/33, grader B
   (Plugins, Verify) 30/32.
5. **Scoreboard 21 to 22:** 43 compared in the scope, 2 better (spec-0359,
   spec-0360: the jar carries the templates), 1 worse (spec-0354).
6. **Posterior:** P(some item of the slice is wrong) = 85.3% (90% interval
   46.1% to 99.9%); one real fail and one the grading room could not grade.

## The fails

- **spec-0354 (real):** a plugin that does not read its standard input can
  make rdm-spec write `ERROR while copying :in option:  Broken pipe` on the
  error stream; with this repository's size of spec (302 scenarios) on every
  run. The scenario's "writes nothing and exits 0" then leaves a stray line.
  **Proposed:** a row of spec-0354 where the plugin reads nothing of its input
  and the event is large: the error stream holds no line from rdm-spec.
- **spec-0364 (grader):** the room holds only the jar, so the "from its
  sources" row could not be graded. It holds when run (integrator). **Proposed
  for the way of working:** a grading room for a scenario about installs gets
  the build's packaged form only, and such a row is graded by the integrator,
  or the scenario names what the grader can see.

## Found by the integrator

- **The `ddd-domain` template declares a command it does not carry:** its
  `template.edn` runs `dhf/harness/plugins/ddd-domain/lint.clj`, which is not
  in `plugins/ddd-domain/` (spec-25's own gap; grader B saw it too).
  **Proposed:** the template carries its `lint.clj` (the rules of the old
  `architecture/domain`), or a scenario says a template's declared command
  exists in it.

## What the scenarios leave unstated (from the graders)

1. **Events beyond `check`:** `status --events` publishes SpecParsed and
   VerificationDerived but runs no subscriber; the rule says "in every task
   that publishes it". State which tasks run subscribers, or make status run
   them.
2. **spec-0351:** the event's scenario maps carry keys beyond events.edn's
   shape (`:tags`, `:row-lines`, ...). State whether the published language
   allows extra keys.
3. **Plugins and their input:** whether a plugin must read its input (the
   cause of the spec-0354 fail).
4. **spec-0352:** "after the core's own stages" has no observable.
5. **spec-0349:** the refusal for a wrongly typed key (a string `:command` is
   refused as "has no :command").
6. **spec-0357:** a Gate plugin exiting 0 with a finding-shaped line has it
   relayed as `warning: plugin-output: ...`.
7. **Lines with no FILE:** `runs-differs`, `probe-failed`, `probe-timed-out`,
   `serves-no-answer` have no FILE, a case spec-0178's form does not cover; a
   failing `:runs` check reports no `:margin`.
8. **spec-0355:** "no process of the plugin" for a backgrounded child (killed,
   left a zombie under PID 1).
9. **spec-0215:** what the room tree hash covers (the implementation paths).
10. **Verify reads the room's committed or working tree;** verify outside the
    project root exits 2.
11. **spec-0197:** whether `test.log` includes the error stream (it does).
12. **spec-0120, 0121, 0187:** "no pin" (`:pins []` or no key), an
    uncommitted drop, and "no checkout given" (no `--sources`, or one without
    that source).
13. **spec-0217:** what verify writes when the cut has an unstamped scenario
    (`ratified.edn` with `:checks []`, no `build/`).
