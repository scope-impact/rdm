# Ticket 04: Trace, a need or a scenario with everything linked to it

Slice: `05-verify-by-results` (`plan.md`, Scope In: "Status, trace"; Goal:
"traces a need to its evidence"); `trace.feature`,
`12-several-specs/trace.feature`. Builds on ticket 03.

## What it delivers

verification/trace's `run`, "rdm-spec trace ID [--results DIR] [--spec
NAME]":

- A need: every scenario that traces to it, by a tag on its feature, its
  rule or itself (spec-0102), each with its id, title, component, context,
  level and status; under `:trace` as spec-0279 states it (G-22 for the
  component's and context's form); none tracing to it said as G-23 settles.
- A scenario: its needs, its component, its bounded context and its level
  (spec-0104), its status and every result behind it with its status and
  file (spec-0105), in the shape G-21 settles.
- Statuses read from verification/status' `derive` (the model's "Reads
  statuses from"); with no results directory, no status derived, saying so
  (spec-0108; ED-09 and G-24 for where it comes from); VerificationDerived
  delivered as G-20 settles.
- Refusals, exit 2: an id that names nothing (spec-0106); a results
  directory that does not exist (spec-0107, the command's own); a need two
  specs hold, with no `--spec`, naming both; a scenario id unique across
  specs traced with none (spec-0409).

## Scenarios it satisfies

- `@spec-0102` Tracing a need lists every scenario that traces to it, however it is tagged, `trace.feature`
- `@spec-0103` A need nothing traces to says so, `trace.feature`
- `@spec-0104` Tracing a scenario names its needs, its component and its bounded context, `trace.feature`
- `@spec-0105` With results, the trace gives each scenario's status and the results behind it, `trace.feature`
- `@spec-0279` A need's trace is one map under :trace, `trace.feature`
- `@spec-0106` An id that names nothing is refused, `trace.feature`
- `@spec-0107` A results directory that does not exist is refused, `trace.feature`
- `@spec-0108` Without results, the trace says no status was derived, `trace.feature`
- `@spec-0409` A need is traced within its own spec, `12-several-specs/trace.feature`

9 scenarios, verified in cycle 6, spec-0108 once ED-09 is settled. This
ticket also unblocks slice 02's spec-0047 (ED-09 too) and slice 04's
spec-0392 and spec-0394 (the component that owns a scenario's file), pending
since cycles 2 and 3.

## Components and interfaces it touches

verification/trace (`verification-trace.run`), verification/status
(`derive`, `deliver`), spec/parser (`specs`, `parse`); kernel/command's
results directory for trace as ED-09 settles.

## Done when

verification/trace's unit tests cover a need tagged on a feature, a rule and
a scenario, a scenario's trace with and without results, each refusal, and
two specs; `wasm/test.sh` writes a passing result for each scenario above;
earlier tickets' results unchanged.
