# Ticket 06: The runner: the step API, discovery, and its refusals

Slice: `05-verify-by-results` (`plan.md`, Scope In: "The runner: one small
step API"); `runner.feature`, Rules "Step definitions are written against
one small API" and "The runner discovers, and refuses to find nothing"; the
glossary's "Step definition" and "Gherkin runner". Builds on ticket 01. Its
step API is ED-02's, stated before the build (G-01, G-02).

## What it delivers

kernel/runner's `definitions` and `run`, "rdm-spec run [--results DIR]":

- The project's step definitions, every file under the configuration's
  `:steps-dir`, found by looking, each run through `rdm:host/process` in the
  project's root as the step API says (ED-02): its definitions asked once per
  run; each pattern matched against a step's text as G-02 settles.
- The spec read with spec/parser (ED-03): every feature file the spec
  holds, found by looking, never listed (spec-0155; G-07 for several specs
  and unstamped scenarios); none found: exit 2, saying no scenario was found,
  no result written (spec-0156).
- Each scenario run step by step, its context starting empty, each step's
  definition given the context and the captured texts, its return the next
  context when it is a map, else the context as it was (spec-0168,
  spec-0169); a step no definition matches, or two do, broken, naming the
  step, or both definitions (spec-0032, spec-0170); the steps after it not
  run. Background steps as G-06 settles.
- One Allure result per scenario written into the results directory the
  command settled (spec-0324; G-03 for one that does not exist, G-04 for
  what an earlier run left there), each with its status and its steps'; its
  every element is ticket 07's. The task's exit and result as G-05 settles.

## Scenarios it satisfies

- `@spec-0168` A step is defined by a pattern and a function, `runner.feature`
- `@spec-0169` Each step's return is the next step's context, and each scenario starts empty, `runner.feature`
- `@spec-0170` A step two definitions match makes the result broken, naming both, `runner.feature`
- `@spec-0032` A step with no definition makes the result broken, `runner.feature`
- `@spec-0155` The runner runs every feature file the spec holds, found by looking, never listed, `runner.feature`
- `@spec-0156` A run that finds no feature file is refused, `runner.feature`
- `@spec-0324` Results go to the configured results directory when RESULTS_DIR is not set, `runner.feature`

7 scenarios, verified in cycle 6 once G-01 is stated (`pending.md`), and
spec-0324 once ED-09 is settled.

## Components and interfaces it touches

kernel/runner (`kernel-runner.definitions`, `run`), spec/parser (`parse`),
`rdm:host/process` (`run`); kernel/command's results directory for run as
ED-09 settles.

## Done when

kernel/runner's unit tests cover pattern matching and captures, the context
handed on (a map, anything else), no match and two matches, a step file that
cannot be started, and no feature file; a fixture's step file written in the
step API runs; `wasm/test.sh` writes a passing result for each scenario
above; earlier tickets' results unchanged.
