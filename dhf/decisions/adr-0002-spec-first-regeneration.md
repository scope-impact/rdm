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
- **Each bounded context is its own container.** A container is something that
  runs or stores data (the C4 definition); each context has its own tasks and
  its own namespaces, and containers relate only through the events in
  `dhf/spec/events.edn`. That makes each context regenerable on its own.
- **Every scenario belongs to exactly one component**, by its file's location:
  `dhf/spec/<container>/<component>.feature`. The component's design prose
  sits beside it as `<component>.md`.
- **The C4 model is one EDN file**, `dhf/spec/model.edn`. Every view is
  generated from it as Mermaid C4 and rendered with `mmdr`
  (mermaid-rs-renderer). Hand-written Mermaid was rejected because each
  diagram would repeat the elements of the others: three hand-kept copies of
  one container. Structurizr DSL was rejected because parsing it needs the Java
  CLI. A linter, part of `rdm-spec check`, holds the model to the C4 skill's quality
  checklist (`scope-impact/agent-skills`, `skills/engineering/c4-diagrams`) and
  to this ADR's container rules, and a render check fails a view whose computed
  layout overlaps a label with a box.

### The harness

- **The harness is specified like every other component.** It defines the
  tags, the verification levels, the Gherkin subset, the Allure contract, the
  black-box rule, the cut, the fence, scoring, the C4 lint and the one
  command. In a product like Löyly's the harness is apart from the product
  and its scenarios must stay out of the product's checklist; in RDM the
  harness *is* the product, so its scenarios are the components' feature
  files under `dhf/spec/`, and `dhf/harness/` holds only its configuration.
  *Amended by the first event storm, hotspot H12
  (`dhf/storm/2026-10-24-rdm.md`): this ADR first kept them in a separate
  `dhf/harness/spec.feature`.*
- **Verification is not only end to end.** Each scenario carries exactly one
  level: `@e2e`, `@contract`, `@example` or `@property`. Persona runs
  (simulated usability) and the mutation probe (a reviewer's tool) are not
  part of rdm-spec: decided by the event storm's hotspots H4 and H5.
- **Allure results are the oracle.** A scenario is verified when a test run
  in any language writes an Allure result whose `spec` label is its id and
  whose status is `passed`. RDM reads only Allure results, never a test
  runner, so a project built on RDM may write its step definitions in any
  stack.
- **Step definitions are black box.** They drive the public surface only (the
  command, the files it reads and writes, and the runner's step API) and never
  load other implementation code. They survive every clean room; the
  implementation does not.
- **The build under test never judges itself.** Ratify and score run from the
  rdm-spec installed outside the clean room, never from the build in it.
  *Simplified after the first review: this ADR first had the previous release
  pinned as a judge, with a bootstrap exception.*
- **One command, `rdm-spec <task>`, installed once.** Hooks, CI, agents, people
  and step definitions all run the same tasks (`dhf/spec/project/tasks.feature`),
  which exit 0 on pass, 1 on a finding and 2 when they cannot run, and name each
  finding on the error stream. Writing the spec is editing files; the command
  only checks, cuts, fences, runs, ratifies, scores and publishes. Nothing per
  project: no task file.

### Roles

- **Every role can be filled by a human or an agent**: engineer, reviewer,
  builder (who regenerates the build) and spec owner (who reads the scoreboard).
  The machinery treats both the same, as Löyly's tutorial says of its builder.
- **Two independence rules hold whoever fills the roles.** A reviewer is not
  the author of the change reviewed. The builder of a cycle never reads that
  cycle's score: the scorer refuses a cycle whose builder is its spec owner.
- **Where this departs from a source.** Löyly's harness says the instrument
  "reports to a person ... and never into the loop". rdm-spec reads "a person"
  as "someone outside the loop", which an agent can be; the independence rule
  is what keeps the instrument out of the loop.

### The way of working

- **Spec first, merge, then regenerate.** A spec change lands as a reviewed
  merge. The merge commit, tagged `spec-N`, is the **cut**: regeneration reads
  that tag and nothing else, in a clean room that is an allow-list (the spec,
  the harness configuration, the raw records, the wiki, the step definitions
  and this ADR), one fresh commit with no history and no network.
- **Regenerate the project, one container or one component.** The model fixes
  where each component's code and unit tests live, so a clean room can drop
  just one part and keep the rest as it was at the cut. Ratify and score always
  run the whole spec, and the scoreboard reports the rebuilt part apart from
  the rest.
- **Anything the delta teaches goes back into the spec** as a new merge. A fix
  made only in regenerated code dies at the next cut, on purpose.
- **There is no planning tool.** A scenario tagged `@wip` is planned work; a
  stamped scenario with no passing Allure result is unfinished work. Backlog.md
  is removed at cutover.
- **Everything is Clojure, run by Babashka**: the core, the harness, the step
  definitions and every script. Shell survives only to install Babashka
  itself. External tools rdm-spec runs but does not write (git, the diagram
  renderer, MkDocs for the site, decided by hotspot H6) are not exceptions:
  rdm-spec writes their input, never their code.

### The sources

- **The spec is distilled from raw sources, and says which.** rdm-spec's are
  three repositories, `innolitics/rdm`, `scope-impact/agent-skills` and
  `skallinen/loyly`, each recorded in `dhf/raw/` with the commit it is pinned to
  and what it is evidence for. They are cited, never copied in and never
  edited, and every user need names the sources it rests on.
- **`scope-impact/rdm` is not a source.** It holds today's implementation of
  what rdm-spec regenerates; its decisions reach the spec through this ADR,
  and none of it is on the clean room's allow-list.
- **A source's implementation is fenced.** Two sources hold implementations of
  what rdm-spec regenerates, and a builder that can read them is copying, not
  rebuilding. Their code paths are listed in `dhf/raw/sources.edn` and never
  cited; no source checkout reaches a clean room, and the clean room has no
  network to fetch one.
- **A domain wiki sits between the sources and the spec** (`dhf/wiki/`), as in
  Löyly: the domain distilled, every claim footnoted to a source, no page
  naming an implementation. Sources and wiki are the `knowledge` container.

### Events

- **Containers meet only on an in-process event bus**, written in Clojure and
  shared by every container as a kernel component (`kernel/bus`), not as a
  container of its own. This is design, not requirement: delivery is
  synchronous and in subscription order, and the bus refuses an event the
  catalogue does not declare, one missing a key it carries, or one from the
  wrong publisher. What the spec requires of it is only what a black box can
  see: every run can print its events, and the same inputs print the same
  events in the same order.
- **How this reads beside "each bounded context is its own container".** A
  task that needs another context's events loads that context into its
  process, and the two meet only on the bus. The lint checks the code keeps to
  this: a container's namespaces never require another container's, and the
  kernel requires no container.

### Scope of the first version

- **In:** reading and checking the spec, stamping, verification and trace from
  Allure results, the cut and the fence, scoring and the scoreboard, the C4
  model with its lint and rendering, publishing a site (Markdown, built to
  HTML by MkDocs).
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
  that check a spec (parser, stamper, spec check) come first: they are what the
  rest of the spec is held to.
- The regulated features leave RDM's own record at cutover and return as an
  optional profile. `rdm init` stops scaffolding them by default.
- `CONTEXT.md` describes the current record and is replaced at cutover by
  `dhf/spec/glossary.md`, which carries this decision's vocabulary from now on.
- The graph and its MCP server either stay on a frozen Python release after
  cutover or are switched off until rebuilt. That is decided before cutover.

## Decided by the agent, on delegation

The remaining hotspots were handed to the agent to decide (2026-10-24). Each
is written into the spec; each can be reversed by a spec change like any
other.

- **The implementation lives in `src/`, `test/` and `test.clj`** (namespaces
  `rdm-spec.*`); step definitions in `dhf/steps/`, which must be written before
  the first regeneration can be ratified.
- **H2, who cuts:** CI, on every merge to the default branch. A cut never
  waits on anyone; cutting twice changes nothing.
- **H3, who drives the builder:** not rdm-spec. rdm-spec stops at a proved
  clean room and starts again at ratification; how a builder, human or agent,
  is run in between is the builder's business. Fewer moving parts in the first
  version, and nothing in rdm-spec that the builder could steer.
- **H7, the first judge:** *simplified after the first review:* there is no
  pinned judge; the installed rdm-spec ratifies and scores, never the build
  under test.
- **H9, unit tests:** run by the declared test command; a failing one refuses
  ratification; their results verify no scenario.
- **H10, the Gherkin runner:** kernel code beside the bus, writing Allure
  results labelled with scenario, component and container. Discovered, never
  listed; refuses a run that finds nothing.
- **H13, label placement:** views stay under the element limit and the render
  check fails what cannot be read; nothing else is built around it.
- **A third source:** `scope-impact/agent-skills`, for what a C4 model is
  (UN-005 now rests on it). It holds no implementation, so nothing is fenced.

## Open

- The spec review. A reviewer other than its author approves the spec before
  it is merged and cut; the author of this spec is an agent, so that agent
  cannot be the reviewer.

## References

- `dhf/decisions/adr-0001-bounded-context-user-needs.md`: the design-input
  model this ADR replaces at cutover.
- `skallinen/loyly`, `README.md` and `harness/README.md`: the regenerate,
  ratify, measure loop and the honesty rules of the instrument.
- `scope-impact/agent-skills`, `skills/engineering/c4-diagrams/reference/c4-guide.md`:
  the C4 model and the quality checklist the linter enforces.
