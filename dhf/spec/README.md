# The spec

The spec of rdm-spec, the source of truth for what it does
(`dhf/decisions/adr-0002-spec-first-regeneration.md`). The vocabulary is
`glossary.md`.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: the container, its bounded contexts and components, and every view |
| `events.edn` | The event catalogue: the events between rdm-spec's bounded contexts, within one run, and their contracts. As rdm-spec's own spec, it is also what a release's bus carries |
| `needs.edn` | The user needs every scenario traces to |
| `ids.edn` | The id ledger; only the stamper writes it |
| `glossary.md` | The vocabulary |
| `<NN-slice>/plan.md` | A slice's plan: its status (planned, ready, building, done), its goal, the needs it rests on, its scope in and out (ADR 0004, ADR 0005) |
| `<NN-slice>/decisions.md` | What was decided in the slice: builder decisions accepted, divergences from its plan (ADR 0005) |
| `<NN-slice>/<component>.feature` | The slice's scenarios of one component, named by the component's name in the model |
| `<NN-slice>/<component>.md` | The component's design prose, where it has any |

rdm-spec's own spec is grouped by slice (ADR 0004): `01-start-a-project`,
`02-write-the-spec`, `03-ground-the-spec`, `04-model-the-architecture`,
`05-verify-by-results`, `06-regenerate`, `07-measure`, `08-plugins`,
`09-publish`, `10-regulated-profile`, `11-project-in-a-folder`,
`12-several-specs`, `13-slice-development`. A component's rules may sit in
several slices, one feature file per slice named for it; the model's spec
path names the slice it started in. Work is slice development (ADR 0005):
a change starts in a slice, and a cycle builds one.

Under the slices layout (`:spec-layout :slices` in the configuration,
spec-30), the spec is grouped by slice first: `<NN-slice>/plan.md` (the
slice's goal, references, scope in and out, and decisions, as Löyly's
`docs/plan/` slices) and `<NN-slice>/<component>.feature`, the slice's
scenarios of one component, named by its name in the model. A component's
scenarios are those of every feature file named for it; a change to any file
of a slice rebuilds the slice whole, every component it reaches.

## Conventions

- **One scenario is one requirement**, tagged with exactly one id
  (`@spec-NNNN`) and one level (`@e2e` or `@contract`). A need tag
  (`@UN-nnn`) on a feature or a rule covers every scenario under it, and a
  scenario may carry its own. `@wip` marks planned work. No other tag is
  allowed.
- **Ids are handed out by the stamper and never reused.** Until the stamper
  exists they are stamped by hand, following its rules
  (`spec/stamper.feature`) and advancing `ids.edn`.
- **A guard cites its incident.** A rule or scenario that exists because
  something once lied says so in a comment above it, with where the incident
  is recorded. A scenario that only says what a component does when nothing
  is wrong cites nothing.
- **The spec never names its stack.** Tools and languages are in
  `dhf/harness/stack.md`; the spec check refuses the configured stack words.
- **Every When runs the command** (`rdm-spec <task>`) unless the step edits a
  file or makes a commit; every refusal says its exit code.
- **Where the spec is silent, follow Löyly.** A builder who meets an
  ambiguity follows the rule in `dhf/wiki/pages/loyly-method.md` and says so
  in the report; the next spec change writes the rule into the spec, citing
  it. Where Löyly is silent too, the builder decides and reports it.
- **"A project" means any repository set up this way,** rdm-spec itself included.
- **Where tags go.** An id (`@spec-` and four or more digits) and a level go
  on a scenario or scenario outline only. A need tag goes on a feature, a rule
  or a scenario. `@wip` goes on a scenario only.
- **ROOM in a step is a new directory the scenario makes for itself,** never a
  fixed path, so runs never share one; so is any other upper-case path
  placeholder (R1, DIR).
- **Published output names paths relative to the project,** never a path of
  the machine it was made on.
- **A scenario's commands inherit the tests' environment, PATH and HOME
  included, except RESULTS_DIR, plus what its steps set;** a variable a
  scenario depends on is set by a step (cycle 08: git and the site builder
  need PATH).
- **A task's result shape is the spec's.** Where another context's step reads
  a task's standard output, the task's feature holds a contract scenario for
  that shape, and the step reads only what it states (cycle 08: spec-0047).
- **A Then that says nothing changed is paired with the outcome the command
  must have,** so a command that never ran cannot pass it (cycle 11:
  spec-0100 passed while publish exited 2).
- **Each rule a file breaks is its own finding,** so one file may have
  several.
- **One step text, one meaning.** A Given worded the same in two feature files
  sets up the same thing in both.
- **How every task reports.** Standard output holds only the task's result, as
  EDN. The error stream holds one line per finding, `FILE:LINE: RULE: what is
  wrong`, then one line per warning, `warning: what`. A RULE is a stable
  kebab-case name the owning component chooses, unless a scenario names it.
  A finding about a file with no line says `FILE: RULE: what`; a relayed
  finding keeps its line unchanged; a message relayed from a tool or a parser
  is one line, each newline in it replaced by a space; on exit 1 standard output still holds the
  result, `{}` when there is none. A warning line is `FILE:LINE: warning: RULE: what`, `FILE: warning: RULE:
what` when no line applies, or `warning: RULE: what` when no file applies; a task that cannot
  run says `cannot-run: why` and prints nothing on standard output; with
  `--events`, each event is a line `event: NAME published-by PUBLISHER`
  (PUBLISHER the component's id without its colon, `spec/parser`), after
  the findings and warnings. Exit 0 is a pass; 1 a finding in what the task checked (the spec, the
  model, the sources, the results, a build); 2 a task that cannot run as
  asked: an unknown task, argument or id, a missing directory or tool, a
  missing or unreadable configuration, a state that forbids it. A missing or
  unreadable file of the spec itself (the ledger, the needs, the model) is a
  finding, exit 1: it is part of what the task checked.
- **How builders, graders and the integrator work is not spec.** It is the way
  of working, kept in `docs/wow/` as Löyly keeps it: `way-of-working.md`,
  `harness.md`, and `invariants.md` with what enforces each rule. The spec
  says what rdm-spec does; a grader holds a build to nothing else.
