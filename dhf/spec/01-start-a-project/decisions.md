# Decisions: slice 01-start-a-project

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `command.feature`, Feature: Command

Kernel component Command: dhf/spec/model.edn, :kernel.

The one entry point a hook, CI, an agent and a person all use: the rdm-spec
command, installed once, with nothing per project. It belongs to no bounded
context: it dispatches each task to the context that owns it.
Specs are written by editing files; the command only checks, stamps, draws,
cuts, fences, runs, verifies, derives checklists, files scores and publishes.
Grading is not a task: a grader grades, however they grade.

### `init.feature`, Scenario: Init takes an absolute path as well as a relative one (spec-0272)

An absolute path leaked an exception (cycle 07, grading).


## Expected decisions (planned for the component build, ADR 0006)

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`; `docs/wow/harness.md`, "The
planner"): each question a builder of this slice would otherwise decide
alone, with its options and the planner's recommendation. The spec owner
settles each before the build, or turns it into a scenario. The tickets
(`ticket-01` to `ticket-05`) cite them as ED-NN.

### ED-01: Scenarios whose task another slice builds

**Settled by the spec owner:** slices are built in dependency order, not by number (ADR 0006, "Build order"); this slice is built fifth, its command first, with slice 02.


Eight of the 23 scenarios this slice's components answer for run a task
another slice builds: spec-0177, spec-0090 (check: slices 02, 03, 04, 06's
paths, 08's plugins), spec-0178 (check: 02, and every stage check runs),
spec-0235 (check: 02 and 04's views), spec-0179, spec-0193, spec-0219
(status: 05), spec-0368 (fence, verify, scopes: 06; checklist: 07). A
slice-scoped verify needs every one of them passing (spec-0404, spec-0208,
spec-0218), so this slice's cycle cannot pass verify before those slices are
built.

- A. A bridge in the product: the command runs a task it does not hold through
  the installed release, removed at the switch. Verify passes now, but those
  scenarios are then verified on the installed release's code, not on this
  build's: a green the build did not earn.
- B. A bridge in the tests only: the steps run the installed release for those
  tasks. Verify passes; a grader driving this build black box sees exit 2, so
  verify and grading disagree.
- C. No bridge: the scenarios run against this build and fail (exit 2, the
  task not built, as the glossary's "Gate" says); the cycle's report names
  them as waiting for their slices; the slice stays `building`, and is
  verified again, without being rebuilt, in the first cycle whose build holds
  their tasks (after slice 08 for all of them).
- D. A spec change: rewrite the scenarios onto tasks this slice holds, or let
  a slice-scoped verify exclude another slice's scenarios. Of these 23, only
  some could be rewritten (no task of this slice finds a problem, exit 1).

Recommendation: C, and the spec owner records in this file that slice 01's
cycle is accepted on its 15 scenarios with the 8 named here pending. A and B
make a green the build did not earn. Givens are a separate question (ED-12).

### ED-02: How the command reaches another slice's tasks (composition)

**Settled by the spec owner:** every slice exports one `tasks` interface (its task-table entries and `run`); the command composes them, and a slice not built answers that it is not built (ADR 0006, "How the command reaches the slices").


kernel/command dispatches every task, but the components owning them come in
later slices, each composed by its own `slice-NN.wac`, and `rdm-spec.wac`
should only gain a line per slice. A component reached through an import must
be there when the command is instantiated.

- A. The command imports each task's interface directly, and each later slice
  edits the command's world and its dispatch. Simple, but every slice's cycle
  rebuilds kernel/command, outside that slice's fence.
- B. One task entry per slice: every slice's composition exports a task entry
  of the same shape (`run: func(task: string, args: list<string>) -> option<...>`
  in builtin types only, see ED-14), and the command imports one per slice,
  named for it, all twelve declared now. A small "not built" component
  answers for each slice not yet built (exit 2). A later slice adds its
  instance line to `rdm-spec.wac` and moves one argument from the stub to
  itself.
- C. A chain: one `tasks` interface that every slice exports and imports,
  answering its own tasks and passing the rest on, the last link the "not
  built" stub. The command imports it once; a slice adds its line and relinks
  one argument.

Recommendation: B, settled before ticket 01, since it changes the command's
world (`dhf/wit/kernel-command.wit`), which then imports the twelve entries.
Until it is settled, ticket 01 builds the command answering a task it does not
hold with exit 2 itself. Neither B nor C keeps `rdm-spec.wac` to adding
lines only: each also changes one argument per slice.

### ED-03: Exit 2 from the core

`wasi:cli/run` returns only success or failure (exit 0 or 1), and
`wasi:cli/exit`'s `exit-with-code` is unstable (feature `cli-exit-with-code`).

- A. Use `exit-with-code`; the host enables the feature in its linker.
- B. An `exit` function in `rdm:host` (the host grows).
- C. The command exports its own entry point returning the code, not
  `wasi:cli/run` (against ADR 0006).

Recommendation: A, with a unit test in the host that 2 reaches the shell.

### ED-04: The hook's executable bit

The filesystem interface the core has carries no permission bits, and git
runs only an executable hook (spec-0025).

- A. Run `chmod +x` on the hook through `rdm:host/process`.
- B. A host function setting a file executable.

Recommendation: A: the host runs what the core asks and holds no logic.

### ED-05: The folders the host opens to the core

The core sees only preopened folders. Init takes an absolute path outside the
working folder (spec-0272); every other task reads its project's root.

- A. Preopen the working folder as `.` and `/` as `/`.
- B. Preopen the working folder, and for init the target's parent: the host
  then reads the task's arguments, which is logic.
- C. The working folder only, refusing any path outside it (spec-0272 fails).

Recommendation: A. Observe (07) still records what a run touches outside its
room.

### ED-06: Which tasks run outside a project's root

The glossary's "Project" says rdm-spec refuses (exit 2) outside a project's
root; init makes a project where there is none, adopt runs in a repository
that holds no configuration yet (spec-0079), and the installed release answers
help anywhere.

- A. help, init and adopt run anywhere; every other task needs the root; an
  unknown task is refused as unknown before the root is looked for (spec-0180
  runs in no project).
- B. Only init and adopt run anywhere.

Recommendation: A; a later spec change writes it into `command.feature`.

### ED-07: What init and adopt print

spec-0086 says init "lists every file it wrote and the next steps", spec-0079
and spec-0080 that adopt lists what it wrote and names what it kept, but no
contract scenario states the shape (`dhf/spec/README.md`: a task's result
shape is the spec's).

- A. The installed release's: `{:init {:path :wrote :next}}` and
  `{:adopt {:wrote :kept :next :gitignore-lines}}`, each kept destination also
  a warning line `FILE: warning: adopt-kept: ...`.
- B. Only what the scenarios name, `:next` dropped from adopt.

Recommendation: A, and a contract scenario for each in this slice (then
`project-adopt`'s `adopted` record gains `next-steps`).

### ED-08: The skeleton adopt adds

Adopt adds "the skeleton, as rdm-spec init writes it" (spec-0079), but
`dhf/spec/model.edn` relates adopt only to hooks.

- A. Adopt imports `project-init.skeleton` (as `dhf/wit/` is written), and the
  model gains `{:from :project/adopt :to :project/init :description "Adds the
  skeleton of"}`.
- B. The skeleton in a library both crates depend on, out of the WIT.
- C. A copy in each.

Recommendation: A: the architecture says it, and the views draw it.

### ED-09: The skeleton's text

spec-0086 says what each file holds, never its text. Its README row cites
`dhf/spec/kernel/runner.feature`, a path that is now
`dhf/spec/05-verify-by-results/runner.feature` in this repository and does not
exist in a new project.

- A. The installed release's skeleton text, checked row by row against
  spec-0086's table, the README naming the results contract without a path.
- B. Written anew from the table.

Recommendation: A; the spec owner corrects the row's path in spec-0086.

### ED-10: The scenario driver behind `wasm/test.sh`

- A. A native test program in `wasm/`, not a component: it finds the feature
  files of the components the build holds by looking, matches each step to
  its definition, drives `wasm/rdm-spec`, and writes the Allure results.
- B. The installed release's `rdm-spec run` with step definitions under
  `test/steps/` (the configuration's `:steps-dir`): ties the new build's tests
  to the old implementation's folder, which the fence removes with it.
- C. Shell scripts.

Recommendation: A, with which components are built named in one file under
`wasm/` that each slice's cycle extends.

### ED-11: Which `rdm-spec` a scenario runs

A scenario inherits the tests' PATH (`dhf/spec/README.md`); the installed
release is on it, and the hook (spec-0025) runs whatever `rdm-spec` PATH finds.

- A. `wasm/test.sh` puts a folder holding `rdm-spec`, a link to
  `wasm/rdm-spec`, first on PATH, so every When and every hook runs this build.
- B. Each step names `wasm/rdm-spec` by its path; hooks would still find the
  installed release.

Recommendation: A. spec-0025 then sets its own `rdm-spec` on PATH, one that
records it was run and exits 1, so it depends on no slice's check.

### ED-12: Givens that need a task

A Given names a state: "a project set up by rdm-spec" before init exists
(ticket 01), and later "a view older than the model" (spec-0235) before
views exists.

- A. The step makes the state by writing files and running git; once this
  build holds the task the Given names, the step runs it ("one step text, one
  meaning": the same state either way).
- B. The step runs the installed release.

Recommendation: A, never B: the installed release is the judge, not a
fixture maker.

### ED-13: Shared kernel code (EDN, the configuration, the report)

Every component reads and writes EDN; several read the configuration. A
crate outside every component's paths in the configuration's `:code-layout`
is never fenced with a component and never in a later slice's paths.

- A. kernel/command's crate is also a library the other crates depend on: the
  glossary's "Kernel" is shared code every context may use.
- B. A crate of its own outside `wasm/components/`.
- C. A copy in each crate.

Recommendation: A.

### ED-14: Types shared between interfaces

Found while composing `dhf/wit/` with stand-in components: a type one
interface uses from another becomes an import of that interface's instance.
The command imports every task, so a task using the command's types (a
`cannot-run` record) is a cycle no composition can wire, and the slice's world
check refuses it.

- A. A task's interface uses only its own types and builtin ones; a refusal
  is a string, the why the command reports as `cannot-run` (as `dhf/wit/` is
  written).
- B. The host gives an empty instance for each types-only interface (the host
  then knows rdm:spec's names).
- C. A types-only component instantiated first (not in the model).

Recommendation: A. Later slices whose tasks report findings define their own
`finding` record, the same shape, until a spec change says otherwise.

### ED-15: The host's `spawn` and `stop`

`dhf/wit/deps/rdm-host/host.wit` declares `run`, `spawn` and `stop`; this
slice needs only `run`, but a linked import must be defined.

- A. Implement all three in ticket 01.
- B. Define `spawn` and `stop` as refusing until slice 06 needs them.

Recommendation: A: they are small and hold no logic.
