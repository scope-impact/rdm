# Ticket 01: The slice composed: the parser, and check with its stages

Slice: `02-write-the-spec` (`plan.md`, Scope In: "The parser: the spec read
at a revision, the Gherkin subset"). Build: the component build of ADR 0006
(`dhf/decisions/adr-0006-wasm-components.md`, cycle 2 of its build order),
its stack in the first section of `dhf/harness/stack.md`. Architecture:
`dhf/wit/` (`events.wit`'s `spec-parsed` and `spec-gated`,
`spec-parser.wit`, `spec-stamper.wit`, `spec-check.wit`, `slice-02.wit`,
`slice-02.wac`; `tasks.wit`, `rdm-spec.wit` and `rdm-spec.wac` as slice 01
left them). Builds on slice 01 as built.

## What it delivers

The whole path through the slice before any rule is checked:

- `wasm/components/spec-parser/`, `spec-stamper/`, `spec-check/` and the
  tasks component (ED-05), each building to its own component that targets
  its world (`spec-parser-component`, `spec-stamper-component`,
  `spec-check-component`, `write-the-spec-tasks`), each world and
  `write-the-spec` given the host's standard interfaces as ED-08 settles. In
  this ticket the stamper answers with the refusal "not built"; ticket 02
  fills it.
- The not-built stand-in also answers for check's stages: `rdm-spec.wit`'s
  `not-built` gains one line, `include check-stages-not-built;`, so each
  stage answers none (ED-02).
- The composition: `wac compose dhf/wit/slice-02.wac` into
  `wasm/build/deps/rdm/write-the-spec.wasm`, and the command with the slice
  added. `rdm-spec.wac` gains exactly these two lines and rewrites none
  (ADR 0006, "How the command reaches the slices"), its instance above the
  command's,

      let write-the-spec = new rdm:write-the-spec { ...not-built, ... };

  and its wiring line inside the command's arguments, above the spread,

      write-the-spec: write-the-spec["rdm:spec/tasks@0.1.0"],

  The slice's instance takes check's stages from the stand-in's spread; a
  later slice that builds a stage adds its own wiring line inside it, by the
  stage's name, ahead of the spread.
- spec/parser, `spec-parser.specs` and `spec-parser.parse` on the working
  tree, one spec (`:spec-dir`): every feature file read in the declared
  subset (Feature, Background, Rule, Scenario, Scenario Outline, Examples,
  Given, When, Then, And, But, tags, comments, doc strings and data tables),
  exactly one Feature per file, a construct outside it refused with its file,
  line and keyword, never skipped; each scenario as `spec-parsed` carries it,
  its context and component from the model where the model owns its file.
- spec/check, `spec-check.run`: the specs' declaration, then every stage in
  ED-03's order, none a gate, a stage answering none refusing the task
  (exit 2, `cannot-run: check's STAGE stage is not built`, nothing on
  standard output); the run's events carried as ED-04 settles, `--events`
  writing one line per event after the findings and warnings; the parser's
  refusals reported as findings (exit 1); its result as ED-06 settles. The
  stage rule (each stage reports, a stage not built refuses) is the kernel's
  shared code (slice 01's ED-13).
- The tasks component's entries, the rows of stamp and check exactly as
  spec-0176 writes them, and `run` handing each to its component.
- `wasm/test.sh` extended to the feature files named for this build's new
  components (`parser`, `stamper`, `check`, in every slice).

## Scenarios it satisfies

- `@spec-0051` A feature file using every supported construct passes the check, `parser.feature`
- `@spec-0052` An unknown keyword is refused with its line, `parser.feature`
- `@spec-0053` A feature file holds exactly one feature, `parser.feature`

3 scenarios, each pending until cycle 7 (`pending.md`): each runs check.

## Components and interfaces it touches

spec/parser (`spec-parser`, `events`), spec/check (`spec-check`,
`check-stages`), spec/stamper as a refusing shell (`spec-stamper`), the tasks
component (`tasks`), the not-built stand-in (`check-stages-not-built`), the
host (`rdm:host/process`, which the parser imports for ticket 06).

## Done when

- `wasm-tools component wit dhf/wit` parses; each crate's component targets
  its world; `wac compose` writes the slice, and the command with the two
  lines above.
- `sh wasm/rdm-spec check` in a project set up by `rdm-spec init` exits 2
  with `cannot-run: check's sources stage is not built` on the error stream
  and nothing on standard output; `sh wasm/rdm-spec help` still lists every
  task as spec-0176 writes it.
- spec/parser's unit tests cover every construct of the subset, an unknown
  keyword and a second Feature; spec/check's unit tests run its stage order
  with test stages: every stage reporting, a stage not built refusing, the
  events handed on in order.
- `wasm/test.sh` runs the three scenarios against this build and reports each
  as it comes out, and slice 01's scenarios still pass.
