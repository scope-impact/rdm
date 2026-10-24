# The spec

RDM's spec, the source of truth for what RDM does
(`dhf/decisions/adr-0002-spec-first-regeneration.md`). The vocabulary is
`glossary.md`.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: every container, component and view comes from here |
| `events.edn` | The events between containers: their contracts |
| `needs.edn` | The user needs every scenario traces to |
| `ids.edn` | The id ledger; only the stamper writes it |
| `<container>/<component>.feature` | The scenarios a component owns |
| `<component>.md` beside a feature | The component's design prose, where it has any |

## Conventions

- **One scenario is one requirement**, tagged with exactly one id
  (`@spec-NNNN`) and one level (`@e2e`, `@contract`, `@example`, `@property`,
  `@persona`). A need tag (`@UN-nnn`) on the feature covers every scenario in
  it.
- **Ids are handed out by the stamper and never reused.** Until the stamper
  exists they are stamped by hand, following its rules
  (`spec/stamper.feature`) and advancing `ids.edn`.
- **A guard cites its incident.** A rule or scenario that exists because
  something once lied says so in a comment above it, with where the incident
  is recorded. A scenario that only says what a component does when nothing
  is wrong cites nothing.
- **"A project" means any repository set up this way,** RDM itself included.
