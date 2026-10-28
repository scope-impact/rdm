# Ticket 03: Adopt: the skeleton added, nothing overwritten

Slice: `01-start-a-project` (`plan.md`, Scope In: "Adopt: the skeleton added
to an existing repository, overwriting nothing, reading none of its code into
the spec"). Builds on ticket 02. `dhf/spec/model.edn`: :project/adopt
"Installs hooks with" :project/hooks; adopt's use of init's skeleton is ED-08.

## What it delivers

- project/adopt, `project-adopt.adopt`: in the current folder, refuses (exit
  2, writing nothing) when it is not a git repository, saying the hook needs
  one; otherwise adds every file of the skeleton, as `rdm-spec init` writes it
  (`project-init.skeleton`, ED-08), and the hook (`project-hooks.install`),
  never over anything already at a destination, a symbolic link included
  (counted without following it, so neither the link nor its target changes);
  names every file it wrote and every destination it kept; when `.gitignore`
  is kept, names the lines init writes to `.gitignore`, which it would have
  added. The spec it adds is the skeleton's, empty whatever the repository
  holds: no feature file, an empty model, no needs, the ledger at 0.
- kernel/command dispatching `adopt` to project/adopt, outside any project
  (ED-06); the shape of what it prints is ED-07.

## Scenarios it satisfies

- `@spec-0079` Adopt adds the skeleton beside what is already there, `adopt.feature`
- `@spec-0080` A destination that already exists is skipped and named, `adopt.feature`
- `@spec-0081` A symbolic link at a destination counts as existing, `adopt.feature`
- `@spec-0082` An existing ignore file is kept, and the lines to add are named, `adopt.feature`
- `@spec-0083` Adopting twice changes nothing, `adopt.feature`
- `@spec-0084` Adopt refuses a directory that is not a git repository, `adopt.feature`
- `@spec-0085` The adopted spec is empty whatever the repository holds, `adopt.feature`

7 scenarios.

## Components and interfaces it touches

project/adopt (`project-adopt`), project/init (`project-init.skeleton`),
project/hooks (`project-hooks.install`), kernel/command (dispatch of `adopt`).

## Done when

`wasm/test.sh` writes a passing result for each scenario above, and the
scenarios of tickets 01 and 02 still pass.
