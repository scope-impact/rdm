# Ticket 02: Each scenario's status, derived from the results

Slice: `05-verify-by-results` (`plan.md`, Scope In: "Results: Allure results
as the oracle, whoever wrote them"; "Status"); `status.feature` and
`results.feature`; the glossary's "Oracle", "Allure result" and "Verified /
failed / unverified / planned". Builds on ticket 01.

## What it delivers

verification/status' `derive` and the status task, for one spec:

- The spec read with spec/parser on the working tree (ED-04): every stamped
  scenario, in the spec's order, with its id, title, file and line.
- Each scenario's status from the results that name it by their spec label
  (the reader's results, ticket 01): planned when tagged `@wip`, whatever
  its results; verified when a result passed and none did not; failed when
  one did not pass (failed, broken, skipped, unknown); unverified with none.
  An outline is one scenario: verified only when every row has a passing
  result, failed when any row's did not pass, else unverified; a result
  counts toward a row only when it names the row by its parameters (G-16).
  Decided by status, spec label and parameters alone (spec-0312).
- A result with no spec label counts toward nothing and is not unknown
  (spec-0164); one naming no stamped scenario listed in `:unknown-results`
  (spec-0060; G-15 for a warning line).
- The task's result exactly as spec-0307 states it, `{:status {:results DIR
  :scenarios [...] :counts {...} :unknown-results [...]}}`, DIR as given,
  exit 0 whatever the statuses; nothing in the spec written (spec-0065).

## Scenarios it satisfies

- `@spec-0307` The status result is exactly this shape, `status.feature`
- `@spec-0064` The status report lists a scenario tagged @wip as planned, `status.feature`
- `@spec-0191` A scenario tagged @wip is planned whatever its results say, `status.feature`
- `@spec-0065` Status is derived from the results, never written, `status.feature`
- `@spec-0066` A scenario outline is one requirement, verified by all its rows, `status.feature`
- `@spec-0302` Results that name no row do not verify an outline, `status.feature`
- `@spec-0285` An outline with a row that has no result is unverified, `status.feature`
- `@spec-0067` A passed result with the scenario's id verifies it, `status.feature`
- `@spec-0068` Any status but passed does not verify (4 rows), `status.feature`
- `@spec-0069` One failing result among several fails the scenario, `status.feature`
- `@spec-0070` A scenario with no result is unverified, never left out, `status.feature`
- `@spec-0060` A result naming no scenario is reported, not counted, `results.feature`
- `@spec-0063` The stack that wrote the results does not matter, `results.feature`
- `@spec-0164` A result with no spec label counts toward nothing and is not reported as unknown, `results.feature`
- `@spec-0312` A result counts by its status, its spec label and an outline row's parameters alone, `results.feature`

15 scenarios, verified in cycle 6.

## Components and interfaces it touches

verification/status (`verification-status.derive`, `run`),
verification/results (`read`), spec/parser (`parse`, as slice 02 built it).

## Done when

verification/status' unit tests cover each status of a plain scenario and an
outline (rows named, not named, missing), a planned scenario with results,
unknown and unlabelled results; `wasm/test.sh` writes a passing result for
each scenario above; earlier tickets' results unchanged.
