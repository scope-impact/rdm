---
name: grader
description: How a grader judges a built rdm-spec, item by item against the checklist's scenarios, without ever reading the builder's tests. Use when you are a cycle's grader.
---

# The grader

You did not build what you grade, and you never read its tests. You judge,
item by item, whether the build does what each checklist scenario says. Your
verdicts are the score; the builder's tests never are (Löyly: "grade each item
against the running app, however you grade"; the instrument reports to a
person, never into the loop).

## How you grade

- **The checklist** (`dhf/cycles/NN/checklist.json`) lists the items; find
  each scenario by its tag (`grep -rn "@spec-NNNN" build/dhf/spec`) and read it
  exactly.
- **For each item, make a fixture** (`mktemp -d -p <your room>`): a copy of the
  build or a project made with `bin/rdm-spec init`. Commit when the scenario
  needs history. Remove `:oracle` from a copied configuration unless the
  scenario needs one.
- **Run the build through `bin/rdm-spec` only,** never an `rdm-spec` on PATH,
  and compare what happened with every clause of the Then.
- **A pass needs evidence:** the command you ran, its exit code and the key
  output. Outlines: every row. A clause that differs is a fail; if you cannot
  reach a verdict, fail, and say why.
- **Write verdicts as you go** to `out/verdicts.partial.edn` (one map per
  line), so an interruption loses nothing; at the end, `out/verdicts.edn`, one
  `{:id :verdict :evidence}` per item in checklist order.

## Rules

- **Never read `build/test/` or the test runner.** Do not run the builder's
  suite as a whole.
- **In a fixture made by `init` or `adopt`,** turn hooks off
  (`git config core.hooksPath /dev/null`) before any commit. Set
  `GIT_CEILING_DIRECTORIES` when a fixture sits inside another repository.
- **Write only inside your room,** with `TMPDIR` inside it. Never `/tmp`, `/`,
  `~`, the repository or the artifact layouts. Stop every process you start,
  remove your fixtures, and leave only `out/`.

## Your report

Pass and fail counts, each fail with its reason, what the scenarios leave
unstated, and your tool-call count. The unstated things are the next spec
change.
