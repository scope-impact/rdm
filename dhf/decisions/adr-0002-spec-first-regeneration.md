# ADR 0002: Spec first, then regenerate

> Status: Proposed. Nothing in this ADR changes how RDM behaves today: the
> current record (`dhf/documents/`) and its gates keep governing this
> repository until the cutover described below. The new spec lives beside it
> under `dhf/spec/` and `dhf/harness/` and is read by nothing in the current
> implementation. It is written in files the current design gate does not
> parse: `.feature` and `.edn` files, and Markdown without frontmatter.

## Context

RDM keeps its requirements as design inputs (`DI-n`) in the frontmatter of one
design document per bounded context, verified by pytest tests tagged
`@allure.story("DI-n")`, and plans work in Backlog.md. The implementation is
Python.

Two things have changed:

1. **RDM is to become the structure other projects are built on**, the way the
   Löyly demonstration (`skallinen/loyly`) shows the method by hand: raw sources
   are distilled into a domain record, the record is the source of truth, and
   the code is a build product that can be deleted and regenerated. Deleting
   and regenerating is a measurement: what a regenerated build gets wrong is
   what the spec failed to carry.
2. **RDM is still greenfield.** There is no released product whose record has
   to stay continuous, so the record can be restarted rather than migrated.

The design input, a frontmatter entry kept apart from the test that verifies
it, is the pattern Löyly's founding incident warns against: one fact in two
hand-kept representations. A Gherkin scenario whose steps are what the test
executes is one representation.

## Decision

### The name

- **The product this ADR describes is rdm-spec.** The suffix says what
  changed: the spec is the source and the code is built from it. `RDM` alone
  keeps meaning today's Python implementation, whose package and `rdm`
  command keep their name until cutover. The Clojure namespaces are
  `rdm-spec.*`.

### The spec

- **Requirements are Gherkin scenarios.** One scenario is one requirement,
  accepted as a whole; its steps are its verification steps. `Rule:` is a
  heading that groups scenarios and carries no id.
- **Ids are stamped, never computed.** Each scenario carries exactly one
  `@spec-NNNN` tag, handed out once by the stamper from a ledger
  (`dhf/spec/ids.edn`) and never reused, not even after the scenario is
  deleted. The id says nothing about where the scenario lives, so moving a
  scenario between components never renames it.
- **There are no aliases.** No `@DI-n` tag survives. The old record stays
  readable at the `pre-spec` tag, created at cutover.
- **The spec is organised by the C4 model.** An event storm produces the
  domain events, commands, policies and aggregates; from them come the C4
  system context (level 1) and containers (level 2), and for each container its
  components (level 3). **Level 3 and the `.feature` files its components own
  are the spec.** Level 4 (code) is never specified: it is what regeneration
  produces.
- **Each bounded context is its own runnable container.** A container is
  something that runs or stores data (the C4 definition), so each context gets
  its own entry point and its own black-box contract, and containers relate
  only through the events and contracts in `dhf/spec/events.edn`. That makes
  each context testable and regenerable on its own.
- **Every scenario belongs to exactly one component**, by its file's location:
  `dhf/spec/<container>/<component>.feature`. The component's design prose
  sits beside it as `<component>.md`.
- **The C4 model is one EDN file**, `dhf/spec/model.edn`. Every view is
  generated from it as Mermaid C4 and rendered with `mmdr`
  (mermaid-rs-renderer). Hand-written Mermaid was rejected because each
  diagram would repeat the elements of the others: three hand-kept copies of
  one container. Structurizr DSL was rejected because parsing it needs the Java
  CLI. A linter, `bb c4 lint`, holds the model to the C4 skill's quality
  checklist (`scope-impact/agent-skills`, `skills/engineering/c4-diagrams`) and
  to this ADR's container rules, and a render check fails a view whose computed
  layout overlaps a label with a box.

### The harness

- **The harness is specified like every other component.** It defines the
  tags, the verification levels, the Gherkin subset, the Allure contract, the
  black-box rule, the cut, the fence, scoring, the C4 lint and the task
  interface. In a product like Löyly's the harness is apart from the product
  and its scenarios must stay out of the product's checklist; in RDM the
  harness *is* the product, so its scenarios are the components' feature
  files under `dhf/spec/`, and `dhf/harness/` holds only its configuration.
  *Amended by the first event storm, hotspot H12
  (`dhf/storm/2026-10-24-rdm.md`): this ADR first kept them in a separate
  `dhf/harness/spec.feature`.*
- **Verification is not only end to end.** Each scenario carries exactly one
  level: `@e2e`, `@contract`, `@example`, `@property` or `@persona`.
- **Allure results are the oracle.** A scenario is verified when a test run
  in any language writes an Allure result whose `spec` label is its id and
  whose status is `passed`. RDM reads only Allure results, never a test
  runner, so a project built on RDM may write its step definitions in any
  stack.
- **Step definitions are black box.** They drive the public surface only (the
  command, the files it reads and writes, the events between containers) and
  never load implementation code. They survive the fence; the implementation
  does not.
- **The previous release judges the next.** When RDM regenerates itself, the
  gates and the scoreboard are run by the pinned previous release, never by
  the build under test.

### The way of working

- **Spec first, merge, then regenerate.** A spec change lands as a reviewed
  merge. The merge commit, tagged `spec-N`, is the **cut**: regeneration reads
  that tag and nothing else, inside a fence that deletes every implementation
  and every measurement from the working tree and from history, and proves it.
- **Anything the delta teaches goes back into the spec** as a new merge. A fix
  made only in regenerated code dies at the next cut, on purpose.
- **There is no planning tool.** A scenario tagged `@wip` is planned work; a
  stamped scenario with no passing Allure result is unfinished work. Backlog.md
  is removed at cutover.
- **Everything is Clojure, run by Babashka**: the core, the harness, the step
  definitions and every script. Shell survives only to install Babashka
  itself.

### The sources

- **The spec is distilled from raw sources, and says which.** rdm-spec's are
  two repositories, `innolitics/rdm` and `skallinen/loyly`, each recorded in `dhf/raw/` with the commit it is pinned
  to and what it is evidence for. They are cited, never copied in and never
  edited, and every user need names the sources it rests on.
- **`scope-impact/rdm` is not a source.** It holds today's implementation of
  what rdm-spec regenerates; its decisions reach the spec through this ADR,
  and its history is fenced as rdm-spec's own.
- **A source's implementation is fenced** like rdm-spec's own: both sources
  hold implementations of what rdm-spec regenerates, and an agent that
  can read them is copying, not rebuilding. Their code paths are listed in
  `dhf/raw/sources.edn`, never cited, and removed in tree and history from the
  source checkouts the clean room carries; the clean room has no network, so
  nothing removed can be fetched back.
- **A domain wiki sits between the sources and the spec** (`dhf/wiki/`), as in
  Löyly: the domain distilled, every claim footnoted to a source, no page
  naming an implementation. Sources and wiki are the `knowledge` container.

### Events

- **Containers meet only on an in-process event bus**, written in Clojure and
  shared by every container as a kernel component (`kernel/bus`), not as a
  container of its own. Delivery is synchronous and in subscription order, so
  a run is deterministic. The bus refuses an event the catalogue does not
  declare, one missing a key it carries, and one published by a container
  other than its declared publisher, and a failing subscriber fails the
  publish.
- **How this reads beside "each bounded context is its own runnable
  container".** Each context keeps its own entry point (its own task) and its
  own namespaces, and is run on its own. When one context needs another's
  events, its entry point loads that context into the same process and the two
  meet only on the bus. The lint checks the code keeps to this: a container's
  namespaces require another container's only through the bus, and the kernel
  requires no container.

### Scope of the first version

- **In:** reading and gating the spec, stamping, verification and trace from
  Allure results, the cut and the fence, scoring and the scoreboard, the C4
  model with its lint and rendering, publishing to Markdown.
- **Out, for now:** the regulated profile (risk register, risk controls,
  compliance checklists, design reviews, the evidence bundle and PDFs), and the
  graph with its MCP server. Each returns later as its own decision.

### Cutover

The Clojure core is built by regeneration from a cut of this spec. It takes
over this repository only when it passes the whole spec at a cut, the harness
spec included. A partial pass means the build is unfinished; the two sets of
gates never run side by side. At cutover the current record is tagged
`pre-spec`, and the design inputs, the Python gates and Backlog.md are
removed.

## Consequences

- The current record keeps governing until cutover. Until then, nothing
  checks the new spec except review and the Gherkin parser, so the components
  that check a spec (parser, stamper, spec gate) come first: they are what the
  rest of the spec is held to.
- The regulated features leave RDM's own record at cutover and return as an
  optional profile. `rdm init` stops scaffolding them by default.
- `CONTEXT.md` describes the current record and is replaced at cutover by
  `dhf/spec/glossary.md`, which carries this decision's vocabulary from now on.
- The graph and its MCP server either stay on a frozen Python release after
  cutover or are switched off until rebuilt. That is decided before cutover.

## Open

- Where the Clojure implementation lives in the tree (`src/` is assumed in
  `dhf/harness/config.edn`).
- The containers themselves: they come out of the event storm, not this ADR.

## References

- `dhf/decisions/adr-0001-bounded-context-user-needs.md`: the design-input
  model this ADR replaces at cutover.
- `skallinen/loyly`, `README.md` and `harness/README.md`: the regenerate,
  ratify, measure loop and the honesty rules of the instrument.
- `scope-impact/agent-skills`, `skills/engineering/c4-diagrams/reference/c4-guide.md`:
  the C4 model and the quality checklist the linter enforces.
