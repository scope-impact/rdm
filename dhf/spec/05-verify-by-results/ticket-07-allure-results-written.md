# Ticket 07: Every result in Allure's own model, from its Gherkin scenario

Slice: `05-verify-by-results` (`plan.md`, Scope In: "one Allure result per
scenario, labelled with what it verifies"); `runner.feature`, Rules "Every
scenario run leaves one result, labelled with what it verifies", "Every
result is written in Allure's own model, from its Gherkin scenario" and "A
scenario's tags are its own and those it inherits"; the glossary's "Allure
result" and "Allure mapping". Builds on ticket 06. spec-0403's last Then runs
verify's tests check (slice 06): pending until cycle 7 (`pending.md`).

## What it delivers

kernel/runner's results, each element filled from the scenario (G-08 for
the project's `:allure` mapping):

- `name`, `fullName` (file, then the title), `historyId` (the id; with an
  outline row's values joined by `|`), `status` and `stage`, `start` and
  `stop` in milliseconds since the epoch, `steps` (one per Gherkin step, in
  order, keyword and text as its name, each with its status, start and
  stop), the labels `feature`, `story` (the rule), `epic` and `suite` (the
  context's id), `subSuite` (the component's id without its colon), one
  `tag` per Gherkin tag without its `@` (the feature's, the rule's, then the
  scenario's own, each once: spec-0403), `framework` `gherkin`, and `spec`,
  `context` (`kernel` for a kernel component's) and `component`; `links`, one
  `issue` per need, its url the configuration's `:needs` and `#UN-nnn`
  (spec-0151, spec-0308).
- An outline: one result per example row, each the outline's id, sharing its
  name, its `parameters` the row's values named by the table's headings, its
  `historyId` the row's (spec-0152, spec-0309).
- A Then whose assertion did not hold: `failed`; anything else that went
  wrong: `broken` (spec-0323); `statusDetails` with the message and the
  trace, the failing step named, the steps after it `skipped` (spec-0153,
  spec-0310).
- A planned scenario: not run, its result `skipped` and labelled `planned`
  `true` (spec-0154; G-11).
- Evidence a step attaches: an attachment of the step, its source a file
  beside the result holding the content, `text/plain` by default
  (spec-0311; G-12).

## Scenarios it satisfies

- `@spec-0151` A scenario's result is labelled with its id, component and context, `runner.feature`
- `@spec-0152` A scenario outline writes one result per example row, all with the outline's id, `runner.feature`
- `@spec-0153` A failing step fails the result and names the step, `runner.feature`
- `@spec-0154` A planned scenario is not run, and its result says it is planned, `runner.feature`
- `@spec-0308` A scenario's result carries each Allure element, filled from the scenario (15 rows), `runner.feature`
- `@spec-0309` An outline row's values are the result's parameters, and its history is the row's, `runner.feature`
- `@spec-0310` A result that did not pass says why, in Allure's statusDetails, `runner.feature`
- `@spec-0311` Evidence a step attaches is an Allure attachment, `runner.feature`
- `@spec-0323` Failed is a Then that did not hold; broken is anything else that went wrong (4 rows), `runner.feature`
- `@spec-0403` A scenario's tag labels are its feature's, its rule's and its own, in that order, `runner.feature`

10 scenarios: 9 verified in cycle 6 once G-01 is stated; spec-0403 pending
until cycle 7 for its last Then.

## Components and interfaces it touches

kernel/runner (`run`'s results), verification/results (a result the runner
wrote, read back: ED-10).

## Done when

kernel/runner's unit tests cover every element of spec-0308's table, an
outline row's parameters and history, failed and broken, a planned result
and an attachment; a result the runner wrote reads back through
verification/results with its status, spec label and parameters, and
status verifies its scenario; `wasm/test.sh` writes a passing result for each
scenario verified in cycle 6; earlier tickets' results unchanged.
