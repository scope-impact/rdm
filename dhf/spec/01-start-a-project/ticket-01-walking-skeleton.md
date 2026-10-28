# Ticket 01: Walking skeleton: the host, the composed command, help

Slice: `01-start-a-project` (`plan.md`, Scope In: "The command: one entry
point, the task table, help"). Build: the component build of ADR 0006
(`dhf/decisions/adr-0006-wasm-components.md`), its stack in
the first section of `dhf/harness/stack.md`. Architecture:
`dhf/wit/` (`kernel-command.wit`, `project-init.wit`, `project-adopt.wit`,
`project-hooks.wit`, `slice-01.wit`, `slice-01.wac`, `rdm-spec.wit`,
`rdm-spec.wac`, `deps/rdm-host/host.wit`).

## What it delivers

The thinnest whole path through the build, end to end, before any task does
real work:

- `wasm/`: the workspace, one crate per component of this slice,
  `wasm/components/kernel-command/`, `project-init/`, `project-adopt/`,
  `project-hooks/`, each building to its own component that targets its world
  in `dhf/wit/` (`kernel-command-component`, `project-init-component`,
  `project-adopt-component`, `project-hooks-component`). In this ticket
  init, adopt and hooks answer every call with the refusal "not built"; tickets
  02 and 03 fill them.
- `wasm/host/`: the native host. It runs `wasm/build/rdm-spec.wasm` with the
  arguments, environment and standard streams it was given, preopens the
  folders ED-05 settles, implements `rdm:host/process` (`run`, `spawn`,
  `stop`; ED-15), and returns the command's exit code, 0, 1 or 2 (ED-03). It
  holds no rdm-spec logic.
- The composition: `wac compose dhf/wit/slice-01.wac` into
  `wasm/build/deps/rdm/start-a-project.wasm`, then
  `wac compose dhf/wit/rdm-spec.wac --deps-dir wasm/build/deps -o wasm/build/rdm-spec.wasm`,
  each checked against the world its `targets` clause names. The dependency
  layout is in the comments of the two `.wac` files.
- kernel/command: the task table, every row of spec-0176 in its order with its
  usage and description exactly as the table writes them; `help` printing
  `{:help [{:task T :usage U :does D} ...]}`; an unknown task refused (exit 2)
  naming it and listing every task; every task of the table this build does not
  hold refused as the glossary's "Gate" says (exit 2, `cannot-run: ...`), until
  ED-02 is settled.
- `wasm/rdm-spec`: the shell script that builds nothing and runs the host on
  `wasm/build/rdm-spec.wasm`.
- `wasm/test.sh`: builds, runs the unit tests, runs the scenarios of every
  feature file named for a component this build holds (`command`, `init`,
  `adopt`, `hooks`, in every slice; ADR 0004), and writes one Allure result per
  scenario and outline row to `$RESULTS_DIR`, as the glossary's "Allure result"
  and `dhf/spec/05-verify-by-results/runner.feature` state the results (ED-10,
  ED-11, ED-12). A scenario whose task this build does not hold yet is run and
  reported as it comes out (ED-01).

## Scenarios it satisfies

- `@spec-0176` Each task is run as rdm-spec <task> (22 rows), `command.feature`
- `@spec-0180` An unknown task exits 2 and lists the tasks, `command.feature`

2 scenarios. Its Given "a project set up by rdm-spec" is made by the step
until ticket 02 builds init (ED-12).

## Components and interfaces it touches

kernel/command (`kernel-command`, `wasi:cli/run`); project/init, project/adopt,
project/hooks as refusing shells (`project-init`, `project-adopt`,
`project-hooks`); the host (`rdm:host/process`).

## Done when

- `wasm-tools component wit dhf/wit` parses, and each crate's component
  targets its world.
- `wac compose dhf/wit/rdm-spec.wac --deps-dir wasm/build/deps -o wasm/build/rdm-spec.wasm`
  writes the command, after the slice's own composition.
- `sh wasm/rdm-spec help` exits 0 and its standard output starts `{:help`,
  within 30 seconds (the configuration's `:verify` `:runs` check "answers").
- `RESULTS_DIR="$(mktemp -d)" sh wasm/test.sh` exits 0 and writes a passing
  result for each row of spec-0176 and for spec-0180, which the installed
  `rdm-spec status --results DIR` reads as verified.
