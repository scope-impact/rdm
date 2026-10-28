# Ticket 02: Init, and the hook it installs

Slice: `01-start-a-project` (`plan.md`, Scope In: "Init: a new project's
skeleton" and "Hooks: every commit checked"). Builds on ticket 01. The
glossary's "Skeleton"; `dhf/spec/model.edn`: :project/init "Installs hooks
with" :project/hooks.

## What it delivers

- project/hooks, `project-hooks.install`: writes `.githooks/pre-commit`, a
  hook that runs `rdm-spec check` and refuses the commit when it exits other
  than 0, executable (ED-04); sets `core.hooksPath` to `.githooks` with git,
  run through `rdm:host/process`; never overwrites a file, naming it as kept;
  refuses, writing nothing, outside a git repository, saying the hook needs
  one.
- project/init, `project-init.skeleton` and `project-init.init`: refuses a
  path that exists (exit 2, naming it, changing nothing); takes a relative or
  an absolute path (ED-05); makes the path a git repository with no commit;
  writes exactly the files of spec-0086's table, each holding what its row says
  (ED-09), the hook by project/hooks; checks the id prefix ("spec" by default;
  a letter first, then letters, digits and single hyphens; never UN); and
  lists every file it wrote and the next steps (ED-07).
- kernel/command dispatching `init PATH [--id-prefix P]` to project/init,
  outside any project (ED-06), and reporting as `dhf/spec/README.md`, "How
  every task reports", states it.
- The step "a project set up by rdm-spec" and "a project just initialised at"
  now run this build's `rdm-spec init` (ED-12).

## Scenarios it satisfies

- `@spec-0086` Init writes the skeleton and names what it wrote, `init.feature`
- `@spec-0272` Init takes an absolute path as well as a relative one, `init.feature`
- `@spec-0087` Init refuses a path that already exists, `init.feature`
- `@spec-0089` The id prefix is chosen at init, `init.feature`
- `@spec-0223` The id prefix defaults to spec and must start a tag (3 rows), `init.feature`
- `@spec-0025` The pre-commit hook runs the check, `hooks.feature`

6 scenarios. spec-0025 needs no other slice: its check is whatever
`rdm-spec` the scenario's PATH finds, and the hook's refusal holds for any exit
other than 0 (ED-11).

## Components and interfaces it touches

project/init (`project-init`), project/hooks (`project-hooks`), kernel/command
(dispatch of `init`), the host (`rdm:host/process` for git).

## Done when

`wasm/test.sh` writes a passing result for each scenario above (every row of
spec-0223), the scenarios of ticket 01 still pass, and project/init and
project/hooks have unit tests for the id prefix rule and for a hook that is
never overwritten.
