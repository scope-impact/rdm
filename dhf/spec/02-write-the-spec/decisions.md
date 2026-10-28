# Decisions: slice 02-write-the-spec

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `check.feature`, Scenario: A tag that is not an id, a level, a need or @wip fails the spec check (spec-0150)

Decided by the event storm's hotspot H4 (dhf/storm/2026-10-24-rdm.md):
there is no persona level.

### `check.feature`, Rule: The spec never names an implementation

The spec never names its stack: a spec that names one has become a
description of one implementation, and regenerating from it measures
nothing (loyly:docs/wow/invariants.md@17d68d2, INV-2). The stack lives in
dhf/harness/stack.md.

### `parser.feature`, Rule: The spec is read at a revision

The parser is ours. A construct it does not know is refused, never skipped:
a scenario the parser silently drops is a requirement nothing checks.
The checklist and ratify read the spec at a cut, not the working tree
(cycle 00, dhf/cycles/00/proposed-spec-changes.md, C20).


## Expected decisions (planned for the component build, ADR 0006)

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`; `docs/wow/harness.md`, "The
planner"): each question a builder of this slice would otherwise decide
alone, with its options and the planner's recommendation. The spec owner
settles each before the build, or turns it into a scenario. The spec owner settled every one on 2026-10-28: ED-02 the other way
from the planner's recommendation, the others as recommended. The tickets
(`ticket-01` to `ticket-06`) cite them as ED-NN; slice 01's are cited as
"slice 01's ED-NN". The architecture is in `dhf/wit/` (`spec-parser.wit`,
`spec-stamper.wit`, `spec-check.wit`, `slice-02.wit`, `slice-02.wac`, and
the records `spec-parsed` and `spec-gated` in `events.wit`).

### ED-01: Scenarios whose task another slice builds

**Settled by the spec owner:** as recommended.

As slice 01's ED-01, settled there: no bridge. This slice's components carry
every scenario they own in any slice, 32 in all (`02-write-the-spec`,
`12-several-specs`, `13-slice-development`). A scenario whose When runs a task
a later cycle builds, or whose Then needs a finding of a stage a later cycle
builds, runs against this build, fails, and is pending: listed in `pending.md` with the cycle that unblocks it. Verify is run
anyway and its record kept; every refusal finding must be a pending
scenario's; the build is graded on the rest; the spec owner decides on that
evidence.

Recommendation: the same as slice 01. Under ED-02 as settled, slice 02's
cycle is decided on its 30 scenarios with the 2 of `pending.md` pending.

### ED-02: How check runs the stages whose components are in later slices

**Settled by the spec owner:** in a partial build, check runs the stages that
exist and, for each stage not built yet, writes one warning line
`warning: stage-not-built: STAGE` on the error stream and goes on; the exit
is what the stages that ran decide. The complete build has every stage, so it
meets the spec (the glossary's "Gate"). In the component build: check imports
one stage interface per later stage (`check-stages`, `spec-check.wit`); the
stand-in (`check-stages-not-built`, included in the not-built stand-in)
answers none for each, and check then writes the warning and runs the next
stage. Options A to D below are the ones this settlement weighed; it is close
to C, with a warning of its own rule name.


`rdm-spec check` is the spec check, the model's lint and the views' freshness
(slice 04), sources and the wiki (slice 03), the clauses (slice 10, only under
`:regulated`, slice 01's ED-17), the paths (slice 06) and the plugins that
subscribe to what it reads (slice 08), every stage reporting (spec-0176,
spec-0235). The command reaches a slice only through its `tasks` (slice 01's
ED-02), so the task check is this slice's, and check must reach the other
stages itself, before their components exist. The glossary's "Gate" says a
stage whose code is not built refuses the task (exit 2): a stage skipped is a
check that reads as a pass.

- A. check imports one stage interface per later stage, named for it
  (`check-stages` in `spec-check.wit`: `sources-stage`, `model-lint-stage`,
  `views-stage`, `wiki-stage`, `clauses-stage`, `paths-stage`,
  `plugins-stage`), the same interface written out each time, in builtin
  types only (slice 01's ED-14). Until a stage's slice is built, a stand-in
  holding no logic satisfies it (`check-stages-not-built`, included in the
  not-built stand-in) and answers none; check then refuses the task, exit 2,
  `cannot-run: check's STAGE stage is not built`, nothing on standard output.
  A slice that builds a stage wires it by name in `rdm-spec.wac`, ahead of
  the spread. Every scenario that runs check is pending until the last stage
  of a run without `:regulated` is built: the paths, cycle 7.
- B. The same imports, the stand-in answering no finding. 23 scenarios pass
  in cycle 2, but a stage skipped reads as a pass: against the glossary's
  "Gate", so it needs a spec change first.
- C. check skips a stage not built and names it in a warning, exiting as its
  built stages decide. The same objection as B, said aloud; also a spec
  change.
- D. check imports later slices' components directly: every later slice's
  cycle rebuilds slice 02, outside its fence.

Recommendation (not taken): A, the glossary as it stands. The order of the stages, and
of check's own refusals, is ED-03. Two consequences the later slices' plans
inherit: a stage is handed what it reads (the run's events, ED-04) and never
imports this slice, or `rdm-spec.wac` holds a cycle no composition can wire;
and slice 06 both serves the paths stage and runs check (cut), so one
instance of slice 06 cannot be both, which its plan settles (for instance,
the paths stage's component instantiated on its own).

### ED-03: The order of check's refusals and stages

**Superseded by the spec change: see Spec gaps.**

**Settled by the spec owner:** as recommended. Under ED-02 as settled, a
stage not built no longer refuses: it is a warning in its place in the order,
so the order decides only where that warning falls among the others.

The installed release reads the plugins' configuration and the specs'
declaration first (a configuration refused, exit 2), then runs sources,
the parser, the spec check, the model's lint, the views' freshness, the wiki,
the clauses (`:regulated` only) and the paths, the plugins receiving the
events as they are published, after the spec check's own stages (spec-0166:
KnowledgeIndexed, SpecParsed, SpecGated, in that order). Under ED-02 A a
stage not built refuses; what is reported when both a refusal of the
configuration and a stage not built hold decides whether spec-0386 passes in
cycle 2.

- A. The specs' declaration first (spec-0386 refused, exit 2, before anything
  runs, as it says), then the stages in the release's order, the first stage
  that answers none refusing the task and discarding what earlier stages
  found. spec-0386 passes in cycle 2. The plugins' configuration is refused by
  the plugins stage, in cycle 5.
- B. Every stage asked first whether it is built, then the configuration:
  spec-0386 waits for cycle 7.

Recommendation: A: "before anything runs" is spec-0386's own wording, and a
refusal names what is wrong whatever is built (slice 01's ED-16).

### ED-04: How the events check publishes are carried before the bus

**Settled by the spec owner:** as recommended (A). A stage not built
publishes nothing, so `--events` lists only what the stages that ran
published.

The parser publishes SpecParsed and check SpecGated (`events.edn`); sources
publishes KnowledgeIndexed, before them. kernel/bus, which delivers each
event to its subscribers within one run and prints them with `--events`
(spec-0166), is slice 08's, built in cycle 5. Within this slice, nothing
subscribes: the stamper and check read scenarios with the parser by calling
it (the model's "Reads scenarios with").

- A. Events are values until the bus exists, and after it: each publisher
  returns what it published (the parser's `parse` returns `spec-parsed`,
  check's `gate` returns `spec-gated`, a stage's answer lists what it
  published); check keeps the run's events in the order published, hands
  them, as EDN (`{:event NAME}` merged with the keys it carries, the
  published language), to every stage after their publisher (the model's
  lint takes SpecParsed from them), writes the `--events` lines itself after
  the findings and warnings, and hands them all to the plugins stage, which
  slice 08 satisfies with kernel/bus and kernel/plugins. Nothing waits for
  the bus but the plugins.
- B. A bus interface imported now by the parser and check (`publish`), a
  stand-in satisfying it until cycle 5 that delivers to nobody and refuses
  `--events`. Truer to "an event travels on the bus", but the interface is
  slice 08's to write, the model's lint would subscribe through the bus,
  and the stand-in drops what it is given.
- C. Every event as EDN text everywhere, `events.wit`'s records unused.

Recommendation: A. The typed records of `events.wit` are the contract within
this slice (the parser exports them, ED-07); a stage of another slice reads
the EDN, the same text a plugin is given on its input.

### ED-05: Who exports the slice's tasks

**Settled by the spec owner:** as recommended (A).

The slice exports one `tasks` (stamp and check), but the model has two
components behind it, spec/stamper and spec/check, and no relationship from
check to the stamper.

- A. A small composition component, `write-the-spec-tasks` (`slice-02.wit`),
  holding no logic: its entries are the table's rows of stamp and check
  (slice 01's ED-18), and it runs stamp with the stamper's `run` and check
  with check's, each component reporting its own outcome.
- B. spec/check exports the slice's tasks and imports the stamper to run
  stamp; the model gains a relationship it has no reason for.
- C. One crate for the three components.

Recommendation: A, like the not-built stand-in, composition and not a
component of the model.

### ED-06: What stamp and check print

**Superseded by the spec change: see Spec gaps.**

**Settled by the spec owner:** as recommended (A). The contract scenario for
stamp's result shape is a proposal for the next spec change, not yet in this
slice's feature files.

No contract scenario states either result's shape (`dhf/spec/README.md`: a
task's result shape is the spec's).

- A. The installed release's: stamp prints `{:stamp {:stamped [{:id :file
  :title} ...]}}` (with `:says` when nothing could be read); check prints the
  merge of what its stages report, `{}` when none reports anything (the
  model's lint reports `{:lint ...}`); the spec check itself reports only
  findings. Rule names of the findings are the installed release's where no
  scenario names one.
- B. Only what the scenarios name: `{}` on standard output for both.

Recommendation: A, and a contract scenario for stamp's result in this slice.

### ED-07: The event records within the slice

**Settled by the spec owner:** as recommended (A).

`spec-parser`'s interface returns `spec-parsed` from `events.wit`, a
types-only interface. A type used from another interface is an import of
that interface's instance (slice 01's ED-14), and nothing in the composition
otherwise exports `events`.

- A. The parser's component exports `events` beside `spec-parser`, so the
  types are its own; the stamper, check and the tasks component import both
  from it (`slice-02.wac`); the slice exports both. Composed with stand-in
  components from the declarations: the slice alone, and the command with
  the slice added.
- B. Each interface writes its own copies of the records (ED-14's A): the
  catalogue then lives in three places.
- C. A types-only component instantiated first (not in the model).

Recommendation: A. Its limit, written in ED-02: a later slice's stage never
uses these types.

### ED-08: The host's standard interfaces in this slice's worlds

**Settled by the spec owner:** as recommended (A).

Cycle 26 is moving the standard interfaces (`wasi:` packages, vendored in
`dhf/wit/deps/`) to version 0.3 while this slice is planned, so this slice's
worlds name none. A composition's `targets` check refuses a component import
its world does not name, so the worlds need the line.

- A. The cycle that builds the slice adds to `write-the-spec` and each
  component world (`spec-parser-component`, `spec-stamper-component`,
  `spec-check-component`) the one line slice 01's worlds carry,
  `include wasi:cli/imports@V;`, V the version they carry then; nothing else
  in this slice's WIT changes.
- B. Name today's version now, and let cycle 26's change rewrite these files
  too.

Recommendation: A.

## Spec gaps

The expected decisions above that leaned on the installed release or on the
spec's silence (`docs/wow/harness.md`, "The planner"). VISIBLE: a grader
driving the command could see the difference, so the spec now states it.
The others are the component build's architecture or partial-build
settlements, which no grader of the complete build sees.

| Decision | Scenarios | What was unstated | Kind | The change |
|---|---|---|---|---|
| ED-03 | spec-0386, spec-0166, spec-0235 | the order of check's stages, and so of its finding lines | VISIBLE | glossary "Finding": a task's findings come in no stated order unless a scenario states one, its warnings after them; spec-0386 already puts the specs' declaration before any stage, spec-0166 the events' order. Chosen differently from the release, whose order is a map's |
| ED-06 | spec-0054, spec-0010, spec-0042 to spec-0053, spec-0134, spec-0150, spec-0186, spec-0209, spec-0234 | stamp's result, check's result, and the rule names of the spec check and the parser | VISIBLE | spec-0426 (stamp's result, `:ledgers` included), spec-0427 (a stamp that cannot read the spec: `:says`, exit 1), spec-0423 (check's result is the lint's alone), spec-0424 (the spec check's eleven rules), spec-0425 (the parser's three, `step-misplaced` among them, a refusal no scenario stated) |

## Settled by the spec owner after the spec gaps (spec-37)

- Findings come in no stated order unless a scenario states one (glossary,
  "Finding").
- An unreadable ledger is a finding, exit 1, as the spec's README states
  (spec-0427); the installed release's exit 2 is its defect, not the spec's.

