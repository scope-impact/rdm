# Ticket 03: Clause items under the regulated profile

Slice: `07-measure` (`plan.md`, Scope In: "The checklist ... (its regulated
rule until 10-regulated-profile holds it)"); `10-regulated-profile/checklist.feature`,
Rule "Under the regulated profile, every clause is an item to grade"; the
glossary's "Clause item", "Clause list" and "Regulated profile"; the
model's ClausesIndexed, published by compliance/gap to checklist. Builds on
ticket 02.

## What it delivers

measurement/checklist's clause items (ED-04):

- Under `:regulated` in the cut's configuration (G-03), and only for a
  cycle scoped to the project or naming the context compliance (the
  glossary's "Clause item"; G-11), the clause index asked at the cut and the
  project's folder: ClausesIndexed as EDN, its `:clauses` read in order.
- Each clause an item after every scenario item: `{"id": KEY, "title":
  DESCRIPTION, "context": "compliance", "component": CLAUSE-LIST,
  "level": "clause"}`, in the clause lists' order with includes in place,
  each key once (spec-0266); the clause list the one the clause is written
  in (ClausesIndexed's `:clause-list`).
- A cycle scoped away from compliance asks nothing and carries no clause
  item (spec-0271).
- The index answering none (not built), with a scope reaching compliance
  under `:regulated`: exit 2, `cannot-run: checklist's clause items are not
  built`, nothing written, since a checklist without them would shrink the
  denominator (the decisions' "An unstamped scenario stops the checklist");
  an error from the index, exit 2 relaying it.

## Scenarios it satisfies

- `@spec-0266` Each clause of the cut's clause lists is an item after the scenarios, `10-regulated-profile/checklist.feature`
- `@spec-0271` A cycle scoped away from the compliance context carries no clause item, `10-regulated-profile/checklist.feature`

2 scenarios: spec-0271 verified in cycle 8; spec-0266 pending until cycle
10, when compliance/gap's clause index is built (`pending.md`). This
ticket is done on unit tests with a stand-in index answering a
ClausesIndexed of two lists, one including the other.

## Components and interfaces it touches

measurement/checklist (`run`), the clause index (`clause-index`, the
not-built stand-in's until slice 10).

## Done when

measurement/checklist's unit tests cover clause items from a stand-in
index (order, includes in place, a key listed twice once), a scope away
from compliance asking nothing, a project without `:regulated` asking
nothing, and the not-built refusal; `wasm/test.sh` writes a passing result
for spec-0271 and reports spec-0266 as it comes out (refused, exit 2, until
cycle 10); earlier tickets' results unchanged.
