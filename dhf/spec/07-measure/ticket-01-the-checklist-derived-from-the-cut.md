# Ticket 01: The slice composed, and the checklist derived from the cut

Slice: `07-measure` (`plan.md`, Scope In: "The checklist, derived from the
cut"); `checklist.feature`, Rules "The checklist is derived from the
cycle's cut" and "An unstamped scenario stops the checklist";
`11-project-in-a-folder/checklist.feature`; the glossary's "Cycle", "Cut"
and "Scope". Build: the component build of ADR 0006
(`dhf/decisions/adr-0006-wasm-components.md`, cycle 8 of its build order),
its stack in the first section of `dhf/harness/stack.md`. Architecture:
`dhf/wit/` (`measurement-checklist.wit`, `measurement-scorer.wit`,
`measurement-scoreboard.wit`, `agency-observe.wit`, `slice-07.wit`,
`slice-07.wac`; `tasks.wit`, `spec-parser.wit`, `regeneration-cut.wit` as
slices 01, 02 and 06 left them). Builds on slices 01, 02, 04, 03, 08, 05
and 06 as built.

## What it delivers

The whole path through the slice before any task does more than its first
step:

- `wasm/components/measurement-checklist/`, `measurement-scorer/`,
  `measurement-scoreboard/`, `agency-observe/` and the tasks component
  (`measure-tasks`, ED-11), each building to its own component that targets
  its world (`measurement-checklist-component`,
  `measurement-scorer-component`, `measurement-scoreboard-component`,
  `agency-observe-component`, `measure-tasks`), each world and `measure`
  given the host's standard interfaces as ED-12 settles. In this ticket
  score, scoreboard and observe answer `cannot-run: TASK is not built yet`
  (exit 2); tickets 04 to 08 fill them.
- The composition: `wac compose dhf/wit/slice-07.wac` into
  `wasm/build/deps/rdm/measure.wasm`, and the command with the slice added.
  `rdm-spec.wac`, as cycles 2 to 7 leave it, gains the lines below and
  rewrites none, and its comment's "Slices built" gains 07; `rdm-spec.wit`'s
  `not-built` gains one line, `include clause-index-not-built;` (ED-04), and
  its comment's "Slices built" gains `07, measure (slice-07.wit)`. The
  stand-in's spread stays: the clauses stage, the clause index, and slices
  09 and 10 are still its. The planner composed exactly this against
  stand-in components built from the declarations: the slice alone, and the
  command with slices 01, 02, 03, 04, 08, 05, 06 and 07 (the not-built
  stand-in with `include check-stages-not-built;` and `include
  clause-index-not-built;`).
- measurement/checklist's `read-cycle`: `dhf/cycles/CYCLE/cycle.edn` in the
  project's folder, its `:cut`, `:scope` (a `:slice` read as
  `[{:slice S}]`), roles and `:bootstrap`; a cycle it cannot read, exit 2
  naming it (G-02).
- measurement/checklist's `run`, for a cycle scoped to the project (ED-03):
  the cut resolved with regeneration/cut's `resolve` (not a cut, exit 2,
  G-02); the cut's configuration read with `show` (G-03); the spec at the
  cut read with spec/parser's `parse` at the cut and the project's folder; a
  scenario with no id refuses, exit 1, naming its file and line, nothing
  written (spec-0211; G-09); else `dhf/cycles/CYCLE/checklist.json` in the
  project's folder: `cycle`, `cut`, `items` (each stamped scenario not
  tagged @wip: `id`, `title`, `context`, `component`, `level`, the model's
  names, G-06) and `planned` (`id`, `title`), keys in that order, items and
  planned sorted by id (spec-0210; G-05); nothing outside the project's
  folder (spec-0406); its standard output as G-01 settles. `derived` reads
  it back.
- The tasks component's entries, the rows of checklist, score, scoreboard
  and observe exactly as spec-0176 writes them, and `run` handing each to
  its component.
- `wasm/test.sh` extended to the feature files named for this build's new
  components (`checklist`, `scorer`, `scoreboard`, `observe`, in every
  slice).

## Scenarios it satisfies

- `@spec-0019` The checklist is derived from the spec at the cut, one item per stamped scenario, `checklist.feature`
- `@spec-0210` checklist.json has one shape, `checklist.feature`
- `@spec-0211` A cut holding an unstamped scenario has no checklist, `checklist.feature`
- `@spec-0406` The checklist of a project in a folder is written in the folder's cycles, `11-project-in-a-folder/checklist.feature`

4 scenarios, verified in cycle 8. This ticket also unblocks slice 02's
spec-0220 (the spec read at the cut) and slice 01's spec-0368, its row
`checklist 03`, which is then verified whole (`pending.md`).

## Components and interfaces it touches

measurement/checklist (`read-cycle`, `derived`, `run`), regeneration/cut
(`resolve`, `show`, as slice 06 built them), spec/parser (`parse`, as slice
02 built it), measurement/scorer, measurement/scoreboard and agency/observe
as shells, the tasks component, the composition (`slice-07.wac`,
`rdm-spec.wac`, `rdm-spec.wit`'s `not-built`).

## Done when

- `wasm-tools component wit dhf/wit` parses; each crate's component targets
  its world; `wac compose` writes the slice, and the command with the lines
  below.
- `sh wasm/rdm-spec help` still lists every task as spec-0176 writes it;
  `sh wasm/rdm-spec checklist 03` on a fixture with a cut and a cycle.edn
  writes its checklist.json.
- measurement/checklist's unit tests cover cycle.edn read and refused
  (missing, not EDN, both `:slice` and `:scope`), a revision that is no cut,
  an unstamped scenario, a planned one, and the key order of the JSON
  written.
- `wasm/test.sh` writes a passing result for each scenario above and for
  spec-0220 and spec-0368, and the scenarios of slices 01 to 06 and 08
  still pass.

## Lines for rdm-spec.wac, as text

The slice's instance, after slice 06's (`let regenerate = ...;`), before
`let start-a-project = ...;`. Its clause index comes from the spread until
slice 10 is built (ED-04):

    let measure = new rdm:measure {
        "rdm:spec/regeneration-cut@0.1.0": cut["rdm:spec/regeneration-cut@0.1.0"],
        "rdm:spec/events@0.1.0": write-the-spec["rdm:spec/events@0.1.0"],
        "rdm:spec/spec-parser@0.1.0": write-the-spec["rdm:spec/spec-parser@0.1.0"],
        ...not-built,
        ...
    };

Inside `start-a-project`'s arguments, above `...not-built,`:

    measure: measure["rdm:spec/tasks@0.1.0"],

In `rdm-spec.wit`, the first line inside `world not-built`'s braces beside
slice 02's `include check-stages-not-built;`:

    include clause-index-not-built;

When slice 10 is built, compliance/gap's instance (on its own, ahead of
slice 05, as slice 05's ED-07 says) gains its wiring line inside this
instance's arguments, ahead of the spread, `clause-index:
gap["clause-index"],`, and rewrites nothing here.
