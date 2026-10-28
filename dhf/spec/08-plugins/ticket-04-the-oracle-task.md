# Ticket 04: The oracle task

Slice: `08-plugins` (`plan.md`, Scope In: "The oracle task: the plugin named
"oracle""); `oracle.feature`, Rule "The oracle task runs the plugin named
"oracle", as it is", and `decisions.md`, why it exists. Builds on ticket 03.

## What it delivers

- kernel/plugins, `run-named`: the plugin of that name, its command with the
  arguments given appended, in order (spec-0343), run in the project's root
  with RDM_SPEC (ED-07) and its `:env`, its run returned as it is; a command
  that cannot be started an error naming it (spec-0345). Its time limit and
  standard input as G-13 settles.
- measurement/oracle, `measurement-oracle.run` (ED-06): `declared` first
  (G-15); the plugin named "oracle" run; its standard output and error stream
  relayed unchanged, and its exit made the task's (spec-0342); no plugin
  named "oracle": exit 0, saying there is no oracle to run, on standard
  output only (spec-0233; G-14 for the result's shape).
- The tasks component's `oracle` entry, as spec-0176 writes it.
- RDM_SPEC answers `help` as `rdm-spec help` does (spec-0344): it starts
  `wasm/rdm-spec` (ED-07), and keeps the caller's TMPDIR (the glossary's
  "Oracle (instrument)").

## Scenarios it satisfies

- `@spec-0342` The oracle task runs the configured command and relays its answer, `oracle.feature`
- `@spec-0343` The oracle task passes its own arguments on, `oracle.feature`
- `@spec-0344` The oracle command is told which rdm-spec it judges, `oracle.feature`
- `@spec-0345` An oracle command that cannot be started cannot run, `oracle.feature`
- `@spec-0233` A project with no oracle is not refused, `oracle.feature`

5 scenarios, verified in cycle 5.

## Components and interfaces it touches

measurement/oracle (`measurement-oracle`), kernel/plugins (`run-named`), the
tasks component.

## Done when

measurement/oracle's unit tests cover a relayed run, an absent oracle and a
command not started; `sh wasm/rdm-spec oracle` in this repository starts
`dhf/harness/oracle/oracle.clj` with RDM_SPEC naming `wasm/rdm-spec`;
`wasm/test.sh` writes a passing result for each scenario above; earlier
tickets' results unchanged.
