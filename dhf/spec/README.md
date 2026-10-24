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
| `<context>/<component>.feature` | The scenarios a component owns |
| `kernel/<component>.feature` | The scenarios of shared code no context owns: the command, the event bus and the Gherkin runner |

## Conventions

- **One scenario is one requirement**, tagged with exactly one id
  (`@spec-NNNN`) and one level (`@e2e` or `@contract`). A need tag (`@UN-nnn`) on a feature or a rule covers every
  scenario under it, and a scenario may carry its own.
- **Ids are handed out by the stamper and never reused.** Until the stamper
  exists they are stamped by hand, following its rules
  (`spec/stamper.feature`) and advancing `ids.edn`.
- **A guard cites its incident.** A rule or scenario that exists because
  something once lied says so in a comment above it, with where the incident
  is recorded. A scenario that only says what a component does when nothing
  is wrong cites nothing.
- **"A project" means any repository set up this way,** rdm-spec itself included.
