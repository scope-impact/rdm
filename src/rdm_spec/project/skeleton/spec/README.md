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

## The results contract

A scenario is verified by Allure results, whatever stack wrote them: one
`<uuid>-result.json` per scenario, and per row of a scenario outline, in
Allure's own model, filled from the Gherkin scenario. Every element a result
carries, and what it is filled from:

| Element | Filled from |
|---|---|
| `name` | the scenario's title |
| `fullName` | the feature file's path and the title, `FILE:TITLE` |
| `historyId` | the scenario's id; an outline row's is the id followed by the row's values, so each row has its own history |
| `status` | `passed`, `failed`, `broken` or `skipped` |
| `stage` | `finished` |
| `statusDetails` | when it did not pass: the message and the trace |
| `start`, `stop` | the scenario's start and stop, in milliseconds since the epoch |
| `steps` | one per Gherkin step, in order: its keyword and text as its name, its status, start and stop, and the evidence it attached |
| `parameters` | an outline row's values, named by the table's headings |
| `labels` | `feature` (the feature's name), `story` (the rule's), `suite` (the bounded context), `subSuite` (the component), a `tag` per Gherkin tag, `framework` `gherkin`, and `spec` (the scenario id, exactly one), `context`, `component`, and `planned` `true` for a scenario tagged `@wip` |
| `links` | one `issue` per need the scenario traces to |
| `attachments` | evidence: a file beside the result, named by its `source` |

A status is decided by `status`, the `spec` label and an outline row's
`parameters` only; everything else is kept for the report. A result with no
`spec` label verifies nothing.
