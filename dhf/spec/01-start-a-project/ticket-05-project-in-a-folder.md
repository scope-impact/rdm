# Ticket 05: A project in one folder of a larger repository

Slice: `01-start-a-project` (`plan.md`, Scope In: "a project in one folder of
a larger repository (until 11-project-in-a-folder holds it)");
`dhf/spec/11-project-in-a-folder/plan.md`; the glossary's "Project". A
regression item: its scenario sits in slice 11, and it is kernel/command's, so
building the command builds it (ADR 0004, ADR 0005). Builds on ticket 04.

## What it delivers

kernel/command's part: the project's root is the folder holding the
configuration, run from that folder whatever the repository's root is; the
folder's path within its repository (git, through `rdm:host/process`) handed
to every task that reads a cut, so it reads the folder at the cut, at its own
root, and nothing outside it; it travels in the request the command runs
the task with (`tasks`). The tasks themselves (fence, verify, scopes,
checklist) are their slices'.

## Scenarios it satisfies

- `@spec-0368` A task that reads a cut reads the project's folder at the cut (4 rows), `11-project-in-a-folder/command.feature` (needs fence, verify, scopes: slice 06; checklist: slice 07)

1 scenario.

## Components and interfaces it touches

kernel/command (its root and the folder's path in the repository); the host
(`rdm:host/process`).

## Done when

kernel/command's unit tests cover a root that is a folder of its repository,
and its path within it; `wasm/test.sh` runs spec-0368's rows against this
build; under ED-01, its fence, verify and scopes rows pass from cycle 7 and
it is verified whole in cycle 8, with checklist (`pending.md`).
