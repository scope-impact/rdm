# Ticket 10: The :plugin check, and CycleVerified delivered

Slice: `06-regenerate` (`plan.md`, Scope In: "Verify: ... the project's
checks (tests, serves, runs, plugin)"); `08-plugins/verify.feature`, Rule
"A :plugin check asks the project's own judge"; `08-plugins/plugins.feature`,
Rule "A plugin can follow a verified cycle"; the glossary's "Plugin" and
"Check"; `events.edn`, CycleVerified. Builds on tickets 08 and 09.

## What it delivers

verification/verify's `:plugin` check and its event (ED-06, ED-08, ED-11):

- Before any check runs, each `{:kind :plugin :name N}` held to a plugin
  named N answering `:Check` in the cut's configuration (G-24): none, exit
  2, saying the check names no plugin answering `:Check`, no check run
  (spec-0363).
- At its place in `:verify`, kernel-plugins' `ask` with the checkout as its
  root and `{:request :Check :cycle CYCLE :cut CUT :checkout
  "dhf/cycles/CYCLE/build" :results "dhf/cycles/CYCLE/results/N"}`, the
  results directory made empty first (spec-0362; G-22): exit 0 `:passed`,
  1 `:failed` relaying its findings unchanged, 2 `:refused` naming the
  plugin, verify exiting 2 after ratified.edn is written (spec-0361; G-29).
- Results the plugin wrote held as the tests check's are, to the mapping,
  every scenario the cycle requires named and passed, else `:failed` naming
  the scenario; none written, judged by its exit alone (spec-0391).
- Once every check passed and ratified.edn is written, CycleVerified, `{:event
  :CycleVerified :cycle :cut :checkout :checks}` with the checks as
  ratified.edn holds them, handed to kernel-plugins' `deliver`: its
  findings are verify's, a subscriber that cannot run makes verify exit 2,
  ratified.edn already written; a verify that did not pass delivers
  nothing (slice 08's spec-0383 and spec-0384, unblocked here).

## Scenarios it satisfies

- `@spec-0361` A :plugin check passes, fails or cannot run as its plugin answers (3 rows), `08-plugins/verify.feature`
- `@spec-0391` A Check plugin's results are held to the mapping, and count, `08-plugins/verify.feature`
- `@spec-0362` A Check plugin is asked in the checkout, with its cycle and cut, `08-plugins/verify.feature`
- `@spec-0363` A :plugin check naming no plugin answering :Check is refused before anything runs, `08-plugins/verify.feature`

4 scenarios, verified in cycle 7. This ticket also unblocks slice 08's
spec-0383 and spec-0384 (`pending.md`).

## Components and interfaces it touches

verification/verify (the `:plugin` check, `verify-events`), kernel/plugins
(`declared`, `ask`, `deliver`, as slice 08 built them), verification/results
(`read`).

## Done when

verification/verify's unit tests cover the Check request's EDN, each exit
of the plugin, a plugin's results held to the mapping, and CycleVerified's
EDN against the catalogue's carried keys; `wasm/test.sh` writes a passing
result for each scenario above and for the scenarios it unblocks; the
whole suite passes, and a run leaves no fixture, checkout, room or process
behind.
