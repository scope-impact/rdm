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
  system context (level 1) and containers (level 2), and for each bounded
  context its components (level 3). **Level 3 and the `.feature` files its components own
  are the spec.** Level 4 (code) is never specified: it is what regeneration
  produces.
- **rdm-spec is a modular monolith.** One container, the `rdm-spec` command,
  made of bounded contexts that are composable rather than separately run:
  each has its own components, namespaces and vocabulary, and contexts relate
  only through the events in `dhf/spec/events.edn`. A context is drawn as a
  boundary around its components, as the C4 skill advises. **Composable** means
  each context can be regenerated alone while the others stand, and the whole
  still ratifies; that is the test, not a lint. *This ADR first made each
  context its own container; the single command made that untrue, and the
  owner chose the modular monolith (second review).*
- **Every scenario belongs to exactly one component**, by its file's location:
  `dhf/spec/<context>/<component>.feature`. The component's design prose
  sits beside it as `<component>.md`.
- **The C4 model is one EDN file**, `dhf/spec/model.edn`. Every view is
  generated from it as Mermaid C4 and rendered with `mmdr`
  (mermaid-rs-renderer). Hand-written Mermaid was rejected because each
  diagram would repeat the elements of the others: three hand-kept copies of
  one container. Structurizr DSL was rejected because parsing it needs the Java
  CLI. A linter, part of `rdm-spec check`, holds the model to the C4 skill's
  quality checklist (`scope-impact/agent-skills`, `skills/engineering/c4-diagrams`)
  and to this ADR's context rules; views are kept small, and a person looks at
  each render.

### The harness

- **The harness is specified like every other component.** It defines the
  tags, the verification levels, the Gherkin subset, the Allure contract, the
  cut, the fence, ratification, grading and scoring, the C4 lint and the one
  command. In a product like Löyly's the harness is apart from the product
  and its scenarios must stay out of the product's checklist; in RDM the
  harness *is* the product, so its scenarios are the components' feature
  files under `dhf/spec/`, and `dhf/harness/` holds only its configuration.
  *Amended by the first event storm, hotspot H12
  (`dhf/storm/2026-10-24-rdm.md`): this ADR first kept them in a separate
  `dhf/harness/spec.feature`.*
- **Verification is not only end to end.** Each scenario carries exactly one
  level: `@e2e` (through the command) or `@contract` (a file format or an
  interface others build on). Persona runs
  (simulated usability) and the mutation probe (a reviewer's tool) are not
  part of rdm-spec: decided by the event storm's hotspots H4 and H5.
- **Allure results are the oracle for status.** A scenario is verified when a
  test run in any language writes an Allure result whose `spec` label is its id
  with status `passed`, and no result for it that did not pass. RDM reads only
  Allure results, never a test runner, so a project built on RDM may test in
  any stack.
- **Tests are the builder's, as in Löyly.** The builder writes its own tests
  from the spec; they are implementation, rebuilt with the code (Löyly's
  INV-5: every scenario has a test, written before the code). There are no
  step definitions in the spec. *Revised by the third review: this ADR first
  kept black-box step definitions beside the spec.*
- **Ratify trusts only the cut.** As Löyly ratifies `impl/` in its trusted
  repository, ratify deletes a fresh checkout's implementation paths and copies
  in the room's, so the spec and the configuration are always the cut's. It
  passes on exactly one condition: the builder's test command exits 0, writes
  Allure results into the cycle, and names every scenario not tagged `@wip`.
  Ratification only gates; it is never the score.
- **Scoring is independent of the builder's tests.** As in Löyly, a grader,
  human or agent and never the builder, grades each item of the checklist
  against the ratified build, however they grade, and `rdm-spec score` files
  the verdicts. No verdicts, no score; an ungraded item is a fail.
- **The first build is a bootstrap.** Before any rdm-spec release exists, no
  installed rdm-spec can ratify or score. The first cycle is marked
  `:bootstrap true` in its `cycle.edn`, is reviewed by a person only, and is
  never a baseline. Its build, once accepted, is the first release installed.
- **One command, `rdm-spec <task>`, installed once.** Hooks, CI, agents, people
  all run the same tasks (`dhf/spec/kernel/command.feature`),
  which exit 0 on pass, 1 on a finding and 2 when they cannot run, and name each
  finding on the error stream. Writing the spec is editing files; the command
  only checks, cuts, fences, runs, ratifies, scores and publishes. Nothing per
  project: no task file. The command is kernel code, in no context: it
  dispatches each task to the context that owns it.
- **The spec never names its stack**, as in Löyly (INV-2). The stack is
  recorded beside the spec in `dhf/harness/stack.md`, which the clean room
  carries; the spec check refuses a stack word anywhere in the spec or the
  wiki. *This ADR first let the spec name its tools; the owner chose Löyly's
  firewall.*

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
  the harness configuration, the raw records, the wiki and this ADR), one fresh
  commit with no history. Giving the builder no
  network is the builder's obligation; the fence cannot enforce it.
- **Regenerate the project, one bounded context or one component.** The
  configuration's `:code-layout` fixes where each component's code and unit
  tests live, so a clean room can drop
  just one part and keep the rest as it was at the cut. Ratify and the
  checklist always cover the whole spec, and the scoreboard reports the rebuilt part apart from
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
  naming an implementation. Sources and wiki are the `knowledge` context.

### Events

- **Contexts meet only on an in-process event bus, within one run.** The bus is
  written in Clojure and shared by every context as a kernel component
  (`kernel/bus`). Steps that run at different times (cut, fence, ratify, score)
  never share a process, so they meet through files: the tag, the clean room,
  `cycle.edn` and the results directory, as Löyly's tools meet through files. This is design, not requirement: delivery is
  synchronous and in subscription order, and the bus refuses an event the
  catalogue does not declare, one missing a key it carries, or one from the
  wrong publisher. What the spec requires of it is only what a black box can
  see: every run can print its events, and the same inputs print the same
  events in the same order.
- **No code-boundary lint.** Löyly has none either. Whether the boundaries hold
  is tested by regenerating one context while the rest stands.

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
over this repository only when a build from a cut is ratified and graded with
every item passing. A partial pass means the build is unfinished; the two sets of
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

## Ambiguity follows Löyly

Decided by the spec owner, 2026-10-25. Where the spec leaves a choice open,
the choice follows Löyly, rdm-spec's raw source for the method. The rules are
distilled, with citations, in `dhf/wiki/pages/loyly-method.md`; the wiki is
on the clean room's allow-list, so every builder can read them, while Löyly's
own tools stay fenced. A builder reports each rule it followed; the next spec
change writes it into the spec. Where Löyly is silent too, the builder decides
and reports it, and the spec owner settles it. Where rdm-spec departs from
Löyly on purpose, the page and this record say so.

## Decided by the agent, on delegation

The remaining hotspots were handed to the agent to decide (2026-10-24). Each
is written into the spec; each can be reversed by a spec change like any
other.

- **The implementation lives in `src/`, `test/` and `test.clj`** (namespaces
  `rdm-spec.*`), the builder's tests among them.
- **H2, who cuts:** CI, on every merge to the default branch. A cut never
  waits on anyone; cutting twice changes nothing.
- **H3, who drives the builder:** not rdm-spec. rdm-spec stops at a proved
  clean room and starts again at ratification; how a builder, human or agent,
  is run in between is the builder's business. Fewer moving parts in the first
  version, and nothing in rdm-spec that the builder could steer.
- **H7, the first judge:** *revised by review:* no pinned judge; the installed
  rdm-spec ratifies against the cut and a grader scores, and the very first
  cycle is a bootstrap reviewed by a person (see "The harness").
- **H9, unit tests:** run by the declared test command with the builder's
  other tests; a failing one refuses ratification; no test ever counts toward a
  score.
- **H10, the Gherkin runner:** kernel code beside the bus, writing Allure
  results labelled with scenario, component and context. Discovered, never
  listed; refuses a run that finds nothing.
- **H13, label placement:** views stay under the element limit and a person
  looks at each render; the render check was removed by the second review.
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
