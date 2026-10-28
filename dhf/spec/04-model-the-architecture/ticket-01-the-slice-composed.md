# Ticket 01: The slice composed: the model read whole, and check's two stages wired

Slice: `04-model-the-architecture` (`plan.md`, Scope In: "The model: read
whole or not at all"). Build: the component build of ADR 0006
(`dhf/decisions/adr-0006-wasm-components.md`, cycle 3 of its build order),
its stack in the first section of `dhf/harness/stack.md`. Architecture:
`dhf/wit/` (`architecture-model.wit`, `architecture-lint.wit`,
`architecture-views.wit`, `slice-04.wit`, `slice-04.wac`; `events.wit` gains
no record, as its header says; `tasks.wit`, `spec-check.wit`, `rdm-spec.wit`
and `rdm-spec.wac` as slices 01 and 02 left them). Builds on slices 01 and 02
as built.

## What it delivers

The whole path through the slice before any C4 rule is checked:

- `wasm/components/architecture-model/`, `architecture-lint/` and
  `architecture-views/`, each building to its own component that targets its
  world (`architecture-model-component`, `architecture-lint-component`,
  `architecture-views-component`), each world and `model-the-architecture`
  given the host's standard interfaces as ED-08 settles. In this ticket the
  lint reports only the model's reading, and the views component answers its
  stage with none and its task as not built (cannot-run); ticket 02 fills
  both.
- The composition: `wac compose dhf/wit/slice-04.wac` into
  `wasm/build/deps/rdm/model-the-architecture.wasm`, and the command with the
  slice added. `rdm-spec.wac` gains these lines, and its comment's "Slices
  built" gains 04 (ADR 0006, "How the command reaches the slices"; ED-02):
  the slice's instance, above slice 02's (an instance is declared before it
  is used),

      let model-the-architecture = new rdm:model-the-architecture { ... };

  the two stage wiring lines inside slice 02's arguments, ahead of the
  spread, slice 02's instance written over several lines to hold them,

      let write-the-spec = new rdm:write-the-spec {
          model-lint-stage: model-the-architecture["model-lint-stage"],
          views-stage: model-the-architecture["views-stage"],
          ...not-built,
          ...
      };

  and the tasks wiring line inside the command's arguments, above the
  spread,

      model-the-architecture: model-the-architecture["rdm:spec/tasks@0.1.0"],

  `rdm-spec.wit` changes only in its comment: "Slices built" gains
  `04, model-the-architecture (slice-04.wit)`. The planner composed exactly
  this against stand-in components built from the declarations (slice 02's
  stand-in with `include check-stages-not-built;`, as slice 02's ticket 01
  adds it).
- architecture/model, `architecture-model.specs` (ED-04) and
  `architecture-model.read`: each spec's `model.edn` read as EDN, whole or not
  at all (ED-07): a file that is not EDN refused with its file and line, an
  unclosed map at the line it opens and a closing bracket that closes nothing
  at its own line, naming the bracket; a key the model's shape does not
  declare (the shape in `model.edn`'s header comment), an id used twice
  anywhere, a relationship or a view naming no element, each its own
  finding, naming what the scenario names; the event catalogue read; the
  model's hash (ED-09). Rule names as ED-06 settles.
- architecture/lint's `model-lint-stage.run`: for every spec, the reading's
  findings, exit 1 through check; reported, `{:lint ...}` (ED-06).
- `wasm/test.sh` extended to the feature files named for this build's new
  components (`model`, `lint`, `views`, in every slice).

## Scenarios it satisfies

- `@spec-0072` A model that is not EDN is refused with its line, `model.feature`
- `@spec-0227` A closing bracket with no opening one is refused with its line, `model.feature`
- `@spec-0073` A key the model's shape does not declare is refused, `model.feature`
- `@spec-0074` An id used twice anywhere in the model is refused, `model.feature`
- `@spec-0075` A relationship naming an element that does not exist is refused, `model.feature`

5 scenarios, verified in cycle 3.

## Components and interfaces it touches

architecture/model (`architecture-model`), architecture/lint
(`architecture-lint`, `model-lint-stage`), architecture/views as a shell
(`architecture-views`, `views-stage`, `tasks`), the composition
(`slice-04.wac`, `rdm-spec.wac`); spec/check's stages as slice 02 built them,
unchanged.

## Done when

- `wasm-tools component wit dhf/wit` parses; each crate's component targets
  its world; `wac compose` writes the slice, and the command with the lines
  above.
- `sh wasm/rdm-spec check` in a project set up by `rdm-spec init` exits 0, its
  error stream no longer naming `model-lint` as not built (`views` still, until
  ticket 02); `sh wasm/rdm-spec help` still lists every task as spec-0176
  writes it.
- architecture/model's unit tests cover the reading's every finding, and the
  hash of this repository's `dhf/spec/model.edn` equal to the one its
  committed views record (ED-09).
- `wasm/test.sh` writes a passing result for each scenario above, and slices
  01's and 02's scenarios still pass.
