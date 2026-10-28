# Decisions: slice 04-model-the-architecture

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `lint.feature`, Rule: The C4 model passes the C4 lint

The rules below are the C4 skill's quality checklist where a machine can
judge it (agent-skills:skills/engineering/c4-diagrams/reference/c4-guide.md@da65901,
"Quality Checklist").

### `lint.feature`, Scenario: A person or system description longer than the configured limit fails the lint (spec-0009)

Found by the first event storm's render (dhf/storm/2026-10-24-rdm.md):
The renderer draws a person's or system's name over a description that wraps
past two lines.

### `lint.feature`, Rule: Bounded contexts are modules of one container, and the model owns the spec

rdm-spec is a modular monolith: one container whose bounded contexts are
composable modules, each drawn as a boundary around its components.

### `lint.feature`, Scenario: A project that lets its contexts relate by call is not held to events only (spec-0339)

The rule is rdm-spec's own architecture, and it bit the first other
project: the Löyly app's booking had to read the schedule through an
event its spec never names (dhf/cycles/loyly-comparison.md, finding 3).
A project declares how its contexts may relate; events only is the
default, and a project's own design record (a domain model, where its
plugin keeps one) says why.

### `model.feature`, Rule: Views go stale when the model's content changes, never its formatting

A hash of the text would mark every view stale after a reformat that
changed nothing, and a gate that cries wolf is a gate that gets skipped.


## Expected decisions (planned for the component build, ADR 0006)

Accepted as recommended by the integrator on 2026-10-28, under the spec
owner's goal (the whole command regenerated in the component build); the
spec owner may overrule any. Proposed for the next spec change: whether a
check passing with no ownership finding is enough for spec-0392 and
spec-0394 (ED-01); keeping or dropping the model's relationship from
spec/parser to architecture/lint (ED-10); a contract scenario for the views
task's result (ED-06); whether "read whole or not at all" stops the lint on
shape findings (ED-07).

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`; `docs/wow/harness.md`, "The
planner"): each question a builder of this slice would otherwise decide
alone, with its options and the planner's recommendation. The spec owner
settles each before the build, or turns it into a scenario. The tickets
(`ticket-01` to `ticket-05`) cite them as ED-NN; slice 01's and slice 02's
are cited as "slice 01's ED-NN" and "slice 02's ED-NN". The architecture is
in `dhf/wit/` (`architecture-model.wit`, `architecture-lint.wit`,
`architecture-views.wit`, `slice-04.wit`, `slice-04.wac`); `events.wit`
gains no record. Slice 02's settlements hold here: a stage not built is a
warning (its ED-02), the events are values handed to each stage as EDN (its
ED-04).

### ED-01: Scenarios whose task another slice builds

As slice 01's and slice 02's ED-01, settled there: no bridge. This slice's
components carry every scenario they own in any slice, 28 in all
(`04-model-the-architecture`, `13-slice-development`). Every When here runs
check or views, both built by cycle 3. Two Thens say which component owns a
feature file (spec-0392, spec-0394): check shows only that no ownership
finding is reported; which component owns a scenario is shown by trace
(slice 05, cycle 6).

- A. spec-0392 and spec-0394 are pending until cycle 6 (`pending.md`); their
  check half is unit-tested and run in cycle 3.
- B. check's exit 0 with no ownership finding is their Then: both verified
  in cycle 3, and the spec owner rewords their Thens to say so.

Recommendation: A, so a grader holds the Then as written; B is a spec change
the spec owner may prefer, since ownership is the model's and trace only
shows it.

### ED-02: How the slice's stages reach check

Slice 02's check imports one interface per later stage (`check-stages`,
`spec-check.wit`), the not-built stand-in answering each until its slice is
built. This slice builds two of them.

- A. architecture/lint exports `model-lint-stage` and architecture/views
  exports `views-stage`, each the stage interface written out as
  `check-stages` writes it; the slice exports both, and `rdm-spec.wac` wires
  them by name into slice 02's instance, ahead of the stand-in's spread
  (ticket 01 gives the lines). The slice imports no other slice: slice 02
  imports its stages, so importing slice 02 would be a cycle no composition
  can wire. Composed by the planner against stand-ins.
- B. A stages component of its own (not in the model) adapting both.
- C. slice 02's check imports this slice's typed interfaces: slice 02 is
  rebuilt in this cycle, outside its fence.

Recommendation: A: each stage is the component's own work, and nothing of
slice 02 changes but the composition.

### ED-03: How views reaches the renderer

**Superseded by the spec change: see Spec gaps.**

The views are rendered by a program the stack names (`dhf/harness/stack.md`,
"Diagram source and render"), which the component build cannot start
itself: it runs through the host's `rdm:host/process` (ADR 0006, "A small
native host").

- A. `run` with the renderer's argument vector, in the project's root, a
  time limit (120 seconds, the installed release's), the diagram source on
  its standard input and the render read from its standard output, which the
  stack's renderer supports; the component writes the source and the render,
  each with its first-line comment. Every view's source and render are made
  before any is written, so a renderer that cannot start (not installed:
  cannot-run, exit 2, saying so) or that fails on a view (cannot-run, its
  error as one line) leaves the views folder as it was.
- B. The renderer writes the render to a file the component names, read back
  and moved into place: temporary files in the project, and a failure can
  leave one behind.
- C. A host function that renders: the host grows, and holds logic.

Recommendation: A. The renderer's name is the views component's own, as a
stack choice lives in the build, never the spec; a configuration key for it
is a spec change if a project ever needs one.

### ED-04: Where the stages find each spec's model

**Superseded by the spec change: see Spec gaps.**

A stage is given check's arguments, the project's folder and the run's
events (`check-stages`); the specs' declaration is spec/parser's
(`spec-parser.specs`), which this slice cannot import (ED-02).

- A. `architecture-model.specs` reads the configuration with the kernel's
  shared code (slice 01's ED-13): each entry of `:specs` its folder's
  `model.edn` and `events.edn`, else `:model` and `:events`; the layout from
  `:spec-layout`; views in `:views-dir`, or `:views-dir/<name>` for each of
  several specs (the installed release's). A declaration check cannot read
  apart has been refused by check before any stage runs (slice 02's ED-03),
  so the stage refuses nothing of it.
- B. check hands each stage the specs declared: `check-stages`' request
  changes, a change to slice 02's WIT and build.
- C. Each stage reads `:model` alone: several specs' models go unchecked
  (against spec-0387).

Recommendation: A.

### ED-05: Who exports the slice's tasks

The slice runs one task, views.

- A. architecture/views exports `tasks` beside its own interface: its one
  entry, the table's row of views (slice 01's ED-18), and `run` for it.
- B. A tasks component holding no logic, as slice 02's ED-05: a component
  more for one task.

Recommendation: A; B if a later spec change gives the slice a second task.

### ED-06: What the lint and the views print, and their rule names

**Superseded by the spec change: see Spec gaps.**

No contract scenario states the views task's result shape, nor the rule
names of the model's reading or of a stale view (`dhf/spec/README.md`: a RULE
is the owning component's choice unless a scenario names it).

- A. The installed release's: check's result gains `{:lint {:for-a-person
  [...] :findings N :warnings N}}` (spec-0010 states `:for-a-person`); views
  prints `{:views {:wrote [path ...]}}`; the rules `model-unreadable`,
  `model-key-unknown`, `model-id-duplicate`, `model-element-unknown`,
  `events-unreadable` and `view-stale`.
- B. Only what the scenarios name: `{:lint {:for-a-person [...]}}`, `{}` for
  views.

Recommendation: A, and a contract scenario for views' result in this slice.

### ED-07: How much of a model with findings is read on

**Superseded by the spec change: see Spec gaps.**

"The model is read whole or not at all" (model.feature). The installed
release reads no further a model that is not EDN, but reports a model read
as EDN with shape findings (an undeclared key, an id twice, an unknown
element) and still lints it and checks its views.

- A. As the release: a model that is not EDN gives its finding alone, and
  no lint, no view checked; a model read as EDN is linted with its shape
  findings beside. The views stage reports nothing for an unreadable model,
  which the model-lint stage has reported, so it is named once.
- B. Any finding of the reading stops the lint and the views' freshness for
  that spec.

Recommendation: A: every finding named in one run (spec-0235's rule), and
the goldens of the installed release unchanged.

### ED-08: The host's standard interfaces in this slice's worlds

As slice 02's ED-08, settled there: the cycle that builds the slice adds to
`model-the-architecture` and each component world the one line slice 01's
worlds carry, in the version they carry then; nothing else in this slice's
WIT changes.

Recommendation: A, as slice 02.

### ED-09: The model's hash, byte for byte

**Superseded by the spec change: see Spec gaps.**

The glossary's "Model hash" prints the model back as EDN, sorted. This
repository's views, and every project's, record the hash the installed
release printed; a printer that differs in one character (a string's
escapes, a keyword, a set, a number) marks every view stale at the switch.

- A. The hash is the installed release's, byte for byte: a unit test holds
  the hash of this repository's `dhf/spec/model.edn` to the one its committed
  views record, and fixtures of every kind of EDN value to the release's.
- B. A new printing, and the views redrawn at the switch (the glossary: a
  change to the definition is cut twice).

Recommendation: A: the definition does not change, so neither may the hash.

### ED-10: Where the lint finds the feature files

**Stated already by spec-0014: see Spec gaps.**

The model has the parser publish SpecParsed to the lint (`model.edn`); the
lint's ownership rules are about files: a component's file that exists, a
file no component owns.

- A. The feature files of the spec's folder, listed by the model component
  (the installed release's): a file with no scenario, or one the parser
  refused, is still owned. SpecParsed, in the stage's events, is read by no
  rule yet.
- B. The files SpecParsed names (its scenarios' and its refusals'): the
  model's relationship honoured, a feature file with no scenario unseen.

Recommendation: A. Then the relationship spec/parser to architecture/lint
describes no use; the spec owner keeps it, for a rule that will read the
scenarios, or drops it in the next spec change.

### ED-11: The diagram source's text

**Open for the spec owner: see Spec gaps.**

spec-0198 says what each level holds, not how it is written; the stack names
the diagram language.

- A. The installed release's text: one line per element and relationship in
  the stack's C4 syntax, the title the view's description, ids written with
  every character outside letters, digits and `_` as `_` and a context's
  namespace joined by `__`, a component view's context drawn as a boundary.
- B. Written anew from spec-0198.

Recommendation: A, so this repository's committed views differ only where
the model does.

## Spec gaps

The expected decisions above that leaned on the installed release or on the
spec's silence (`docs/wow/harness.md`, "The planner"). VISIBLE: a grader
driving the command could see the difference, so the spec now states it.
FREE: no grader could, so it stays the builder's.

| Decision | Scenarios | What was unstated | Kind | The change |
|---|---|---|---|---|
| ED-03 | spec-0017 | what views leaves behind when the renderer cannot start or fails | VISIBLE | spec-0437: exit 2, and no file under the views directory changes. Chosen differently from the release, which writes the diagram source before it finds no renderer. How the renderer is called, and its time limit, are FREE |
| ED-04 | spec-0017, spec-0387 | where each of several specs' views are written | VISIBLE | spec-0436: `:views-dir/<name>`, nothing in `:views-dir` itself. How the configuration is read is FREE |
| ED-06 | spec-0010, spec-0017, spec-0018, spec-0072 to spec-0075 | the lint's counts, the views task's result, the rules of the model's reading and of a stale view | VISIBLE | spec-0430 (`{:for-a-person :findings :warnings}`, the reading's findings counted), spec-0434 (views' result, its order), spec-0431 (five rules, `events-unreadable` among them, a finding no scenario stated), spec-0435 (`view-stale`) |
| ED-07 | spec-0072, spec-0073 | whether a model with findings is still linted and its views checked | VISIBLE | spec-0432: a model read as EDN is linted beside its shape findings; one that is not EDN gives its finding alone |
| ED-09 | spec-0017, spec-0077, spec-0078 | the model hash's printing, byte for byte | VISIBLE | glossary "Model hash": the printing stated exactly (separators, escapes, keywords, UTF-8); spec-0433 pins one model's hash. Both reproduce the release's hash, so no view goes stale |
| ED-10 | spec-0013, spec-0014 | whether a feature file with no scenario is owned | VISIBLE | none: spec-0014 says "a feature file in the spec", whatever it holds, so option A is already the spec |
| ED-11 | spec-0198 | the diagram source's text | VISIBLE | for the spec owner: the text is in the stack's diagram language, which the spec may not name. Either a view is held only to spec-0198 and its first line (and the oracle compares no more of it), or the diagram text becomes a stack contract kept in `dhf/harness/`, outside the spec |
