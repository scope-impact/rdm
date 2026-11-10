# ADR 0002: The spec lives with the factory; RDM audits it

> Status: Accepted and implemented (2026-11-10). RDM reads a Löyly-shaped spec
> (`rdm/specification/spec.py`); design inputs are gone from RDM and from its
> own record, whose spec is now in Löyly's layout under `.pi/`. Against
> Löyly's `spec-v2` the reading gives 201 stamped items, Löyly's own golden.
> Decided along the way, superseding the sections below where they differ:
> a run claims a requirement with an Allure `tag` label `item-NN-SSS`; a user
> need is an event-model screen with the Allium surfaces it links (a surface,
> where a context has no screens), captured with the event storm, not the
> Gherkin Feature; RDM's own record was converted in one go. Open: whether
> Löyly's graded verdicts become a second kind of evidence.

## Context

RDM owns the specification today. A project writes **design inputs** (`DI-n`, a
`design_inputs:` list in one `kind: design` document per bounded context), traces each to a
user need, tags one pytest test `@allure.story("DI-n")` per input, and lets the design
gate, `rdm story new-input`, the release gate, `verify`, the pytest plugin, the graph, the
evidence report and the risk register's `controls:` all take that list as their denominator.
RDM's earlier model dropped Gherkin on purpose: "the test IS the acceptance criterion".

The agentic software factory in [scope-impact/loyly](https://github.com/scope-impact/loyly)
takes the opposite position, and runs it end to end:

- **The docs are the source of truth and the code is a build product.** The layers are
  `raw/` (immutable sources) → `docs/wiki/` (the domain, each claim citing a source) →
  slice `plan.md` → `spec.feature` → tickets → `impl/`. In the harness's own spec
  (`.pi/spec/`, spec v2), each bounded context is a slice with its Allium (rules,
  invariants), its Grain Event Model, its public seam and its `decisions.md`.
- **The behaviour spec is Gherkin.** Each accepted scenario is stamped `@item-NN-SSS`
  (NN is the slice, SSS the scenario). The stamper hands out each id once. It never
  renumbers or reuses an id, and rewording a scenario keeps its id. Drafts stay in
  `drafts.feature`.
- **The checklist is derived, never written.** There is one item per stamped scenario of
  every slice not `planned` or `backlog`. An unstamped scenario or an empty selection stops
  the cycle.
- **Regeneration is the measurement.** The cycle goes fence → regenerate → gate → score →
  scoreboard. Each slice is fenced into a clean room, rebuilt by a fresh builder, ratified
  by a gate the builder does not control, and scored by sealed graders. The scoreboard
  compares shared items in both directions. What the delta teaches goes back into the
  docs, never only into the code.
- **Tests answer to scenarios.** Every e2e test names the scenario it implements
  (`docs/wow/invariants.md`, INV-5), and Allure results are reconciled with the model
  through the `@item-*` ids (`.pi/model/README.md`).

Running both models means authoring the same requirement twice: once as a stamped scenario,
where it is regenerated and measured, and again as a `DI-n` in RDM, where it is gated. These
are hand-kept copies of one fact, the drift Löyly's own founding story warns against.

## Decision

**Ownership splits along one line. The factory (Löyly) authors the spec and regenerates the
code. RDM reads the spec, never writes it, and audits it.** RDM keeps two jobs:

1. **Gap analysis.** Regulation → checklist → clause ← controlled document (`rdm gap`, the
   graph's checklist part). This is unchanged.
2. **Traceability and audit from Allure.** Stamped item → test run (Allure) → result →
   commit. RDM reads the factory's spec as it finds it and turns it into evidence: the
   matrix, the release gate, the risk view, the graph, the report and the bundle.
   A stamped scenario verifies the user need its `Feature:` states.

### What RDM reads instead of design inputs

- **The requirement is the stamped scenario.** Its id is the `@item-NN-SSS` tag. Its text is
  the scenario title, under its `Rule:` and `Feature:`, in the slice that holds it. RDM
  reads `spec.feature` files with **one Gherkin reading**, the factory's. It follows the
  same rules as Löyly's checklist: the last `@item` tag before a `Scenario` wins, slices
  marked `planned`/`backlog` in `plan.md` are skipped, and `drafts.feature` is never read.
  An unstamped scenario is a finding, never a quiet omission.
- **The release denominator is the stamped items of the accepted slices.** That is the
  factory's checklist, read the same way, so RDM's denominator and the scoreboard's cannot
  drift apart.
- **A test run claims an item through an Allure `tag` label `item-NN-SSS`.** This is what
  allure-pytest-bdd, allure-cucumber and allure-behave already emit from scenario tags. A
  pytest test without Gherkin can claim an item with `@allure.tag("item-01-003")`. The
  `story` label stops being RDM's trace key (`DESIGN_INPUT_LABELS` becomes an item label
  switch).
- **The id grammar admits the factory's ids.** `kernel/ids.py` currently needs an upper-case
  prefix. It gains the `item-\d+-\d+` shape, set in configuration so that another
  factory's ids can be read too.
- **Risk controls name items.** `risks[].controls: [item-04-012]`. The rules are unchanged:
  an unknown item is UNKNOWN_CONTROL, and an item with no passing run is CONTROL_UNVERIFIED.

### What goes from RDM

- `design_inputs:` in design documents, `sdd.design_inputs()` and `realises:`.
- `rdm story new-input` and its stub test. The factory scaffolds scenarios and stamps them
  (its stamper).
- The design gate's checks that police authoring: INPUT_UNTAGGED, malformed or duplicate
  design-input declarations, NEED_UNTRACED. Duplicate ids become a read-time finding
  ("this item id is stamped twice"), which the factory's stamper already prevents.
- `rdm:DesignInput`, `tracesTo`, `ownedBy`, `verifies` and `namesInput` in the ontology,
  replaced by an `rdm:SpecItem` (with its slice, rule and source line) that `exercises`
  points at.
- The pytest plugin's run-time labels that were derived from the DI record (epic = user
  need, feature = context, severity from controls). Its version, commit and worktree
  labels stay.

### What stays as it is

`rdm gap` and the checklists, Allure ingestion (runs, steps, attachments, status, commit and
worktree facts), rendering and Pandoc/Typst, the graph's document, commit and checklist
parts, C4 component labels, the mutation probe, unit coverage, validation and persona
records, and the pull-request review as independent verification.

### User needs: the event model, Allium and the event storm

**Decided (product owner, 2026-11-10), superseding "the Feature is the user
need":** a user need is captured by the event storm, the event model and
Allium together. RDM takes each **screen** of a context's event model as a
user need, with the Allium **surfaces** it links (the actor each faces, the
rules whose triggers it provides, what it guarantees) and the **event-storm
events** an actor causes that the screen's commands produce. A context with no
screens offers each surface as a need, its doc comment as its text.

- A requirement **addresses** a need when its `@allium-*` tags cover one of the
  need's rules or guarantees; one that covers none (a system-facing seam, an
  invariant) traces to the needs of its own context.
- The release gate blocks a need that no requirement addresses directly.
- Validation records name the need's id (`measurement/instrument-desk`).

## Consequences

- One copy of each requirement. Changing a scenario changes the denominator RDM gates on,
  with no second edit.
- RDM's surface shrinks to what an auditor asks: is every clause addressed, is every item
  run and passing at the released commit, is every risk control verified, and who approved
  it. Authoring guidance moves to the factory and to scope-impact/agent-skills.
- **RDM's own DHF has to move too (dogfood).** Its 69 design inputs and 36 tagged
  acceptance files become stamped scenarios in slices, one per bounded context, with the
  tests tagged `item-NN-SSS`. Either that is done in one reviewed change, or RDM keeps a
  compatibility reader for `design_inputs:` through one release (question 3).
- Breaking change for adopters: `rdm adopt`/`init` templates, `gates.yml`, the traceability
  matrix template (`verification.groups[].design_inputs` → `items`) and the docs (IFU-001)
  all change. Release as v3.

## Migration, in order (each step a reviewed PR with the gates green)

1. **Read the factory's spec.** Add a Gherkin item reader to `rdm/specification` matching
   Löyly's checklist rules, accept `item-NN-SSS` in `kernel/ids.py`, and read Allure `tag`
   labels. These are additive, with acceptance tests against a Löyly-shaped fixture
   (`docs/plan/NN-slug/spec.feature` + `plan.md`).
2. **Switch the denominator.** `release-gate`, `verify`, `trace`, the report, the bundle and
   the risk register take items from the reader. `design_inputs:` is read only through the
   compatibility path, if question 3 keeps it.
3. **The graph.** Add `rdm:SpecItem`, `exercises`, the citation chain to wiki/raw, and update
   the shapes (`DesignInputVerifiedShape` → `ItemVerifiedShape`, and so on) and the MCP
   `trace`.
4. **Convert RDM's own DHF** to slices with stamped scenarios, and retag
   `tests/acceptance/`.
5. **Remove** `new-input`, the authoring checks of the design gate, the DI ontology terms,
   the plugin's DI-derived labels and the `design_inputs:` reader. Update `CONTEXT.md`
   (retire *design input*, *baseline/risk-based acceptance criterion*, *realising context*;
   add *spec item*, *slice*, *stamp*), the templates, the docs and the CHANGELOG.

## Open questions (decide before step 1)

1. **Trace key on the Allure side.** Should a run claim an item by an Allure `tag` label
   `item-NN-SSS` (proposed; Gherkin runners emit it with no glue), or by a dedicated
   `allure.label("item", "01-003")`?
2. **RDM's own record.** Should RDM's DHF be converted to stamped Gherkin in one go, or
   should a read-only `design_inputs:` path stay for one release while adopters migrate?
3. **The factory's measurement as evidence.** Should RDM also ingest Löyly's filed
   measurement (graded verdicts per item, the scoreboard) as a second, independent kind of
   verification evidence beside the Allure runs, or keep the instrument out of the record
   as Löyly does (the score reports to a person, never into the loop)?
4. **Allium and the Event Model.** Should RDM project Allium rules and invariants (and the
   `@allium-*` tags on scenarios) into the graph so an auditor can see rule → scenario →
   run, or stop at the scenario?
