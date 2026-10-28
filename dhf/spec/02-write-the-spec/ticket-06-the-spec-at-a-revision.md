# Ticket 06: The spec read at a revision

Slice: `02-write-the-spec` (`plan.md`, Scope In: "The parser: the spec read
at a revision"); the slice's `decisions.md`, "Rule: The spec is read at a
revision". Builds on ticket 04.

## What it delivers

spec/parser, `spec-parser.parse` with a revision (a commit or a cut,
`spec-N`): every spec read as it was at that revision, through git run by
`rdm:host/process`, never the working tree, the project's folder within its
repository read at its own root where it is one (the folder the command
hands a task, slice 01's ticket 05); `revision` in `spec-parsed` the commit
read. An unknown revision cannot run (exit 2, naming it). The tasks that read
a cut (checklist, fence, verify, scopes) are their slices'; this ticket gives
them the reading.

## Scenarios it satisfies

- `@spec-0220` A task that names a cut reads the spec as it was at that cut, `parser.feature`

1 scenario, pending until cycle 8 (`pending.md`): its When runs checklist.

## Components and interfaces it touches

spec/parser (`parse`), the host (`rdm:host/process`, for git).

## Done when

spec/parser's unit tests read a fixture's spec at a tag holding one scenario
fewer than its working tree, and in a project that is a folder of its
repository; `wasm/test.sh` runs spec-0220 against this build and reports it
as it comes out; earlier tickets' results unchanged.
