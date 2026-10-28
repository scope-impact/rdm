# Ticket 05: Scopes, from the spec change, in build order

Slice: `06-regenerate` (`plan.md`, Scope In: "Scopes: what a spec change
touched, in the order a builder builds it"); `scopes.feature`,
`11-project-in-a-folder/scopes.feature`, `13-slice-development/scopes.feature`;
the glossary's "Scope", "Event catalogue" and "Slice". Builds on ticket 01.

## What it delivers

regeneration/scopes' `touched` and `run`, "rdm-spec scopes CUT CUT" (G-20
for what the two cuts may be):

- The paths of the project's folder the two cuts differ by (cut's
  `changes`), nothing outside the folder (spec-0407); each cut extracted to
  a temporary directory under TMPDIR and read with architecture/model (its
  model, its catalogue, its ownership), the directories removed before it
  returns (ED-03).
- A changed feature file, or a component's design prose
  (`<component>.md`, spec-0241), makes its component a scope: the
  to-cut's owner, the from-cut's for a file deleted (ED-05).
- The event catalogues compared event by event: a change to an event's
  publisher, its carried keys or their shape, or an event added or removed,
  reaches its publishers (both, when it moved: spec-0348) and every
  subscriber the model's relationships name for it (G-18); a change to its
  description, or to a key the catalogue's entry does not declare, reaches
  none (spec-0340).
- Under the slices layout, a change to any file of a slice's folder makes
  the slice a scope, `{:slice S :components [...]}`, with every component
  it reaches that no earlier slice of the listing holds, slices in the order
  of their folders' names (spec-0398, spec-0399, spec-0416; ED-05; G-10,
  G-19).
- The order a builder builds them: the kernel first, then each publisher
  before its subscribers, the rest in the model's order (spec-0299; ED-05).
- A changed file no component owns named as a shared file, a warning line,
  exit 0 (spec-0238; G-17); the result on standard output as G-16 settles.

## Scenarios it satisfies

- `@spec-0237` The components whose feature files changed are listed as scopes, `scopes.feature`
- `@spec-0241` A component's design prose is its own, `scopes.feature`
- `@spec-0238` A changed file no component owns is named for a person to judge, `scopes.feature`
- `@spec-0298` A changed event's publisher and subscribers are scopes, `scopes.feature`
- `@spec-0348` A changed publisher reaches both publishers and every subscriber, `scopes.feature`
- `@spec-0340` A change that leaves an event's contract unchanged reaches no component (2 rows), `scopes.feature`
- `@spec-0299` The kernel first, then each publisher before its subscribers, `scopes.feature`
- `@spec-0407` Scopes read the change within the project's folder only, `11-project-in-a-folder/scopes.feature`
- `@spec-0398` A change to any file of a slice makes the slice a scope, with every component it reaches, `13-slice-development/scopes.feature`
- `@spec-0399` Two slices touched are two scopes, a component they share listed in the first, `13-slice-development/scopes.feature`
- `@spec-0416` A component with feature files in two slices is reached by both, `13-slice-development/scopes.feature`

11 scenarios, verified in cycle 7. This ticket also unblocks slice 01's
spec-0368 (row scopes) (`pending.md`).

## Components and interfaces it touches

regeneration/scopes (`touched`, `run`), regeneration/cut (`changes`,
`tree`, `extract`), architecture/model (`specs`, `read`, `owners`).

## Done when

regeneration/scopes' unit tests cover the build order on spec-0299's model,
an event's contract changed and unchanged, a moved publisher, a deleted
feature file and two slices sharing a component; `wasm/test.sh` writes a
passing result for each scenario above; no temporary directory is left
behind; earlier tickets' results unchanged.
