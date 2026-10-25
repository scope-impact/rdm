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
- **"A project" means any repository set up this way,** rdm-spec itself included.
- **Where tags go.** An id (`@spec-` and four or more digits) and a level go
  on a scenario or scenario outline only. A need tag goes on a feature, a rule
  or a scenario. `@wip` goes on a scenario only.
- **ROOM in a step is a new directory the scenario makes for itself,** never a
  fixed path, so runs never share one.
- **One step text, one meaning.** A Given worded the same in two feature files
  sets up the same thing in both.
- **How every task reports.** Standard output holds only the task's result, as
  EDN. The error stream holds one line per finding, `FILE:LINE: RULE: what is
  wrong`, then one line per warning, `warning: what`. A RULE is a stable
  kebab-case name the owning component chooses, unless a scenario names it.
  Exit 0 is a pass; 1 a finding in what the task checked (the spec, the
  model, the sources, the results, a build); 2 a task that cannot run as
  asked: an unknown task, argument or id, a missing file, directory or tool,
  an unreadable configuration, a state that forbids it.
