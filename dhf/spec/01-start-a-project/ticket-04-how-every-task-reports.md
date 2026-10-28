# Ticket 04: How every task reports, met through other slices' tasks

Slice: `01-start-a-project` (`plan.md`, Scope In: "how every task reports and
exits, a gate stopping a task"); `dhf/spec/README.md`, "How every task
reports"; the glossary's "Gate" and "Project". Builds on ticket 01. Every
scenario here is the command's or init's, but its When runs a task another
slice builds: each is pending (ED-01), listed in `pending.md` with the cycle
that unblocks it.

## What it delivers

kernel/command's part of every task, built and unit-tested now, whatever
task it runs:

- The project's root: the folder the command runs in must hold
  `dhf/harness/config.edn`, else `cannot-run` (exit 2) for every task but
  help, init and adopt (ED-06); the configuration read as EDN (ED-13).
- The results directory of a task that takes `[--results DIR]`: `--results`,
  else the environment's `RESULTS_DIR`, else the configuration's
  `:results-dir`; a directory that does not exist refused with exit 2, naming
  it, before the task is run (ED-16). The command hands the directory it
  settled to the task in the request it runs it with (`tasks`).
- Reporting: a task's outcome (`tasks`: exit code, standard output, error
  lines) written as it came; standard output only the result as EDN (`{}` on
  exit 1 when there is none, nothing on `cannot-run`); one error line per finding
  (`FILE:LINE: RULE: what`, FILE and LINE left out where none applies), then
  one per warning; exit 0, 1 or 2 as the README states.
- The task table's stages: each task's stages in order, a gate stopping the
  task when it has findings that are not warnings, every other stage reporting
  so one run names every finding, and a stage this build does not hold
  refusing the task (exit 2), as a task not built is answered (ED-02).

## Scenarios it satisfies

- `@spec-0177` A task that passes exits 0, `command.feature` (needs check: slices 02, 03, 04, 06, 08)
- `@spec-0178` A task that finds a problem exits 1 and names it on the error stream, `command.feature` (needs check: 02, and every stage check runs)
- `@spec-0235` A task with no gate reports the findings of every stage, `command.feature` (needs check: 02 and the views' freshness, 04)
- `@spec-0179` A task that cannot run exits 2 and says why, `command.feature` (needs status: 05)
- `@spec-0193` A task's results directory defaults to the configured one, `command.feature` (needs status: 05)
- `@spec-0219` A task's results directory is --results, else RESULTS_DIR, else the configured one (3 rows), `command.feature` (needs status: 05)
- `@spec-0090` The check passes on a skeleton nobody has touched, `init.feature` (needs check: 02, 03, 04, 06, 08)

7 scenarios.

## Components and interfaces it touches

kernel/command (`kernel-command`: `finding`, `cannot-run`; `tasks`: its
request and outcome, and the dispatch, ED-02); the configuration reader shared as kernel code (ED-13).

## Done when

kernel/command's unit tests cover the root, the results directory's order,
the report's format and exit codes, and a gate stopping a task, with test
stages; `wasm/test.sh` runs the seven scenarios against this build and reports
each as it comes out; and, under ED-01, each is verified in the cycle
`pending.md` names for it (cycle 6 for status's, cycle 7 for check's).
