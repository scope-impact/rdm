# Ticket 02: The stamper: each id once, from the ledger

Slice: `02-write-the-spec` (`plan.md`, Scope In: "The stamper: ids handed out
once, from the ledger"). Builds on ticket 01. `dhf/spec/model.edn`:
:spec/stamper "Reads scenarios with" :spec/parser, and "Stamps features and
advances the ledger in" the repository.

## What it delivers

- spec/stamper, `spec-stamper.stamp` and `spec-stamper.run`: reads the spec
  with the parser; gives every scenario with no id tag the next id after the
  highest the ledger (`ids.edn`) records, in the configured id prefix
  (`:id-prefix`), written as a tag on the scenario's line in its feature
  file, four digits or more; advances the ledger to the last id issued; never
  issues an id the ledger has issued, a deleted scenario's included; leaves a
  scenario that carries an id as it is, whatever its title or its file;
  changes no file when every scenario carries an id. Refuses to stamp a spec
  the parser cannot read (nothing written).
- The task `rdm-spec stamp`, run through the tasks component, its result as
  ED-06 settles.

## Scenarios it satisfies

- `@spec-0054` The stamper gives every unstamped scenario the next id from the ledger, `stamper.feature`
- `@spec-0055` Stamping twice changes nothing, `stamper.feature`
- `@spec-0056` A deleted scenario's id is never issued again, `stamper.feature`
- `@spec-0057` The id does not depend on the wording or the location, `stamper.feature`
- `@spec-0058` The id prefix is configuration, `stamper.feature`

5 scenarios, none pending: stamp runs no stage of another slice.

## Components and interfaces it touches

spec/stamper (`spec-stamper`), spec/parser (`spec-parser.parse`), the tasks
component (dispatch of `stamp`).

## Done when

`wasm/test.sh` writes a passing result for each scenario above, ticket 01's
still run as before, and spec/stamper has unit tests for the next id, the
ledger never going back, and a file left byte for byte when nothing is
stamped.
