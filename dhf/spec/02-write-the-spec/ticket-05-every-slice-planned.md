# Ticket 05: Every slice planned, with a status and its decisions

Slice: `02-write-the-spec` (`plan.md`, Scope In: "The spec check");
`dhf/spec/13-slice-development/plan.md`; ADR 0004, ADR 0005. Regression
items: the scenarios sit in slice 13, and they are check's. Builds on
ticket 03.

## What it delivers

spec/check, `spec-check.gate`, under `:spec-layout :slices`, for each slice
folder of the spec:

- `plan.md` with the headings "Goal", "References", "Scope" and "Decisions",
  its References naming at least one declared need, its Scope with "In" and
  "Out": else `slice-unplanned` (no plan, a heading missing) or
  `slice-unfounded` (no need named, or a need that is not declared), naming
  the plan.
- The line under the plan's title, `status: planned`, `ready`, `building` or
  `done`: else `slice-status`, naming the plan (and the four statuses when
  the line holds another).
- A slice whose status is done holding a scenario tagged `@wip`:
  `slice-done-planned`, naming the plan and the scenario.
- A slice with a plan and no `decisions.md`: `slice-undecided`, naming the
  slice's folder.

## Scenarios it satisfies

- `@spec-0400` A slice's plan says its goal, its scope and what it rests on, `13-slice-development/check.feature`
- `@spec-0401` A slice that is not planned is a finding (4 rows), `13-slice-development/check.feature`
- `@spec-0411` A slice's status is one of four, under its plan's title (6 rows), `13-slice-development/check.feature`
- `@spec-0412` A slice holding planned work is not done, `13-slice-development/check.feature`
- `@spec-0413` Every slice keeps its decisions, `13-slice-development/check.feature`

5 scenarios, verified in cycle 2.

## Components and interfaces it touches

spec/check (`gate`), spec/parser (each scenario's file, for its slice).

## Done when

spec/check's unit tests cover each rule and each row above; `wasm/test.sh`
writes a passing result for each scenario above, every row included;
earlier tickets' results unchanged.
