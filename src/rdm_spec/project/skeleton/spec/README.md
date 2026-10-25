# The spec

The source of truth for what the product does. The code is built from it,
never the other way round. The vocabulary is `glossary.md`.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: the system, its containers, bounded contexts and components, and every view |
| `events.edn` | The events between bounded contexts, within one run: their contracts |
| `needs.edn` | The user needs every scenario traces to |
| `ids.edn` | The id ledger; only the stamper writes it |
| `glossary.md` | The vocabulary |
| `<context>/<component>.feature` | The scenarios a component owns |
| `<context>/<component>.md` | The component's design prose, where it has any |

## Conventions

- **One scenario is one requirement**, accepted as a whole; its steps are its
  verification steps. `Rule:` is a heading that groups scenarios and carries
  no id.
- **Every scenario carries exactly one id** (`@<prefix>-NNNN`, the prefix in
  `dhf/harness/config.edn`) and **exactly one level**: `@e2e` (through the
  command) or `@contract` (a file format or an interface others build on).
- **Ids are handed out by `rdm-spec stamp`** from the ledger and never
  reused, not even after their scenario is deleted. Never write one by hand.
- **Every scenario traces to a user need**, by a need tag (`@UN-nnn`) on
  itself, its rule or its feature; every need rests on a source.
- **`@wip` marks planned work**, on a scenario only. No other tag is allowed.
- **Every scenario belongs to one component**, by its file's location:
  `<context>/<component>.feature`, as the model names it.
- **The spec never names its stack.** Tools and languages are in
  `dhf/harness/stack.md`; the spec check refuses the configured stack words.
- **A guard cites its incident**: a rule or scenario that exists because
  something once went wrong says so in a comment above it.
- **`rdm-spec check` holds the spec to these rules** on every commit (the
  pre-commit hook).
