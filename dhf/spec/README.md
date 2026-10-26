# The spec

The spec of rdm-spec, the source of truth for what it does
(`dhf/decisions/adr-0002-spec-first-regeneration.md`). The vocabulary is
`glossary.md`.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: the container, its bounded contexts and components, and every view |
| `events.edn` | The events between bounded contexts, within one run: their contracts |
| `needs.edn` | The user needs every scenario traces to |
| `ids.edn` | The id ledger; only the stamper writes it |
| `glossary.md` | The vocabulary |
| `<context>/<component>.feature` | The scenarios a component owns |
| `<context>/<component>.md` | The component's design prose, where it has any |
| `kernel/<component>.feature` | The scenarios of shared code no context owns: the command, the event bus and the Gherkin runner |

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
- **One builder per cycle.** Never contexts in parallel: a cycle's scopes are
  built one after another by one builder, in the order `scopes` lists them
  (spec owner, cycle 13; dhf/cycles/retrospective-01-12.md).
- **Every run is observed** (dhf/spec/agency/observe.feature): its capability
  is proven by its result, and its defence is recorded beside it.
- **A golden holds nothing the spec does not state.** Before a golden is
  recorded or re-recorded, every key it holds traces to a scenario (cycle 11:
  the scorer's and scoreboard's goldens held shapes no scenario stated).
- **Step definitions are the scope's own.** A step file uses only the
  kernel's shared steps and its own context's step files; a helper it needs
  from elsewhere is copied in. A step text used by the features of more than
  one context is the kernel's; one used within one context is that
  context's. Its owner defines it once, when its step file loads, never only
  inside another step's body (cycle 03: four integration fixes; cycle 06:
  spec-0090 and spec-0187 found no definition).
- **A scenario's project is one the scenario makes,** a fixture, never the
  room or the repository the tests run in, whose files change from cut to
  cut and differ in ratify's checkout. The ids, needs, pages and commits a
  scenario names are its fixture's (cycle 06: spec-0231; cycle 07:
  spec-0239).
- **A fixture copied from the room holds what a clean room holds:** the
  paths the configuration keeps, never the denied ones (cycle 13: the copy
  still read the old allow-list).
- **A fixture never carries the room's oracle** unless its scenario sets
  one: the oracle a fixture's configuration names is the scenario's (cycle
  09: 22 fixtures reached for the room's registry).
- **A Given builds on the project an earlier Given of its scenario made;**
  only the first Given makes one (cycle 11: a shared Given threw away the
  history spec-0236's first Given made).
- **A Then that says nothing changed is paired with the outcome the command
  must have,** so a command that never ran cannot pass it (cycle 11:
  spec-0100 passed while publish exited 2).
- **A shared step's context keys are the kernel's.** The kernel's step file
  names, beside each shared step, the keys it sets; another step file reads
  only those (cycle 09: `:wiki-file` read as `:wiki-rel`).
- **A suite removes every fixture it makes,** unit tests included (cycle 06:
  the disk filled; cycle 07: 8,934 directories left behind).
- **Each rule a file breaks is its own finding,** so one file may have
  several.
- **One step text, one meaning.** A Given worded the same in two feature files
  sets up the same thing in both.
- **How every task reports.** Standard output holds only the task's result, as
  EDN. The error stream holds one line per finding, `FILE:LINE: RULE: what is
  wrong`, then one line per warning, `warning: what`. A RULE is a stable
  kebab-case name the owning component chooses, unless a scenario names it.
  A finding about a file with no line says `FILE: RULE: what`; a relayed
  finding keeps its line unchanged; on exit 1 standard output still holds the
  result, `{}` when there is none. A warning line is `FILE:LINE: warning: RULE: what`, `FILE: warning: RULE:
what` when no line applies, or `warning: RULE: what` when no file applies; a task that cannot
  run says `cannot-run: why` and prints nothing on standard output; with
  `--events`, each event is a line `event: NAME published-by PUBLISHER`, after
  the findings and warnings. Exit 0 is a pass; 1 a finding in what the task checked (the spec, the
  model, the sources, the results, a build); 2 a task that cannot run as
  asked: an unknown task, argument or id, a missing directory or tool, a
  missing or unreadable configuration, a state that forbids it. A missing or
  unreadable file of the spec itself (the ledger, the needs, the model) is a
  finding, exit 1: it is part of what the task checked.
