# Ticket 07: The tests check, on the results of every scenario in scope

Slice: `06-regenerate` (`plan.md`, Scope In: "Verify: ... the project's
checks ... run the same way and recorded"); `verify.feature`, Rule "The
tests check passes only when the tests pass and every scenario passed";
`12-several-specs/verify.feature`, `13-slice-development/verify.feature`;
the glossary's "Check", "Oracle" and "Allure result". Builds on ticket 06.

## What it delivers

verification/verify's `:tests` check (ED-06, ED-08):

- The cycle's results directory, `dhf/cycles/CYCLE/results` in the
  project's folder, emptied and made before the command runs (G-22); the
  command run in the checkout through the host with RESULTS_DIR its
  absolute path (spec-0206), its output written to
  `dhf/cycles/CYCLE/test.log` (G-33).
- Its exit not 0: the check `:failed`, exit 1, saying the test command
  failed with its exit status and naming test.log (spec-0197); the record
  `:outcome :refused` with the reason (spec-0216; G-28).
- The results read with verification/results' `read` (slice 05): none, or
  no directory, is "a green exit with no results is not a run", exit 1
  (spec-0033); an unreadable file as G-25 settles.
- The scenarios the cycle's scope requires (G-23): every stamped scenario
  not tagged `@wip` of the spec at the cut; of the spec `:scope [{:spec
  S}]` names (spec-0410); of every component the slice reaches, in any
  slice, for `:scope [{:slice S}]` or `:slice S` (spec-0404; ED-05). Each
  named by a result whose spec label is its id, and every result naming it
  passed (G-26 for an outline's rows): else exit 1, naming each scenario
  with no result (spec-0208) and each that did not pass with its status
  (spec-0218).
- The check's entry `{:name "tests" :kind :tests :outcome :seconds
  :scenarios N}`, N the scenarios required that results name (spec-0207);
  the first check that fails stops the gate, the later ones listed
  `:not-run` (spec-0315; G-29).

## Scenarios it satisfies

- `@spec-0206` The builder's tests are told where to write their results, `verify.feature`
- `@spec-0207` The tests check passes when the builder's tests pass and every scenario's results passed, `verify.feature`
- `@spec-0197` A failing test command fails verification, `verify.feature`
- `@spec-0033` The tests check requires results, `verify.feature`
- `@spec-0208` A scenario no test names fails verification, `verify.feature`
- `@spec-0218` A failed or broken result fails verification, `verify.feature`
- `@spec-0216` A failed verification is recorded with its reason, `verify.feature`
- `@spec-0315` The first check that fails stops the gate, and the rest are named as not run, `verify.feature`
- `@spec-0410` A cycle scoped to one spec is verified on that spec's scenarios, `12-several-specs/verify.feature`
- `@spec-0404` A cycle scoped to a slice is verified on the scenarios of the components it reaches, `13-slice-development/verify.feature`

10 scenarios, verified in cycle 7.

## Components and interfaces it touches

verification/verify (the tests check), verification/results (`read`, as
slice 05 built it), spec/parser (`parse`).

## Done when

verification/verify's unit tests cover the scenarios each scope requires,
a scenario with no result, a failed and a broken one, an empty results
directory and a test command that exits 1; `wasm/test.sh` writes a passing
result for each scenario above; earlier tickets' results unchanged.
