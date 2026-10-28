# Ticket 01: The slice composed, the git helpers, and check's paths stage

Slice: `06-regenerate` (`plan.md`, Scope In: "Fence: a clean room, the cut
minus what is denied"); `fence.feature`, Rule "Every committed path is
denied or kept"; the glossary's "Fence" and "Cut". Build: the component
build of ADR 0006 (`dhf/decisions/adr-0006-wasm-components.md`, cycle 7 of
its build order), its stack in the first section of `dhf/harness/stack.md`.
Architecture: `dhf/wit/` (`regeneration-cut.wit`, `regeneration-scopes.wit`,
`regeneration-fence.wit`, `verification-verify.wit`, `slice-06.wit`,
`slice-06.wac`; `tasks.wit`, `spec-check.wit`, `spec-parser.wit`,
`architecture-model.wit`, `verification-results.wit` and `kernel-plugins.wit`
as slices 01 to 05 and 08 left them). Builds on slices 01, 02, 04, 03, 08 and
05 as built.

## What it delivers

The whole path through the slice before any task does its work:

- `wasm/components/regeneration-cut/`, `regeneration-scopes/`,
  `regeneration-fence/`, `verification-verify/` and the tasks component
  (`regenerate-tasks`, ED-12), each building to its own component that
  targets its world (`regeneration-cut-component`,
  `regeneration-scopes-component`, `regeneration-fence-component`,
  `verification-verify-component`, `regenerate-tasks`), each world and
  `regenerate` given the host's standard interfaces as ED-13 settles. In
  this ticket cut, scopes, fence and verify answer `cannot-run: TASK is not
  built yet` (exit 2); tickets 02 to 10 fill them.
- The composition (ED-01): `wac compose dhf/wit/slice-06.wac` into
  `wasm/build/deps/rdm/regenerate.wasm`; cut and fence built to
  `wasm/build/deps/rdm/regeneration-cut.wasm` and `regeneration-fence.wasm`;
  and the command with the slice added. `rdm-spec.wac`, as cycles 2 to 6
  leave it, gains the lines below and rewrites none, and its comment's
  "Slices built" gains 06; `rdm-spec.wit` changes only in its comment
  ("Slices built" gains `06, regenerate (slice-06.wit)`); `events.wit`'s
  header comment names `verify-events` (ED-11). The stand-in's spread
  stays: the clauses stage, and slices 07, 09 and 10, are still its. The
  planner composed exactly this against stand-in components built from the
  declarations: the slice alone, and the command with slices 01, 02, 03, 04,
  08, 05 and 06 (the not-built stand-in with `include
  check-stages-not-built;`).
- regeneration/cut's git helpers whole (ED-03, ED-04): `resolve`, `tree`
  (a revision, or the index), `show`, `extract` (committed bytes exactly,
  the executable bit, links as links, no history) and `changes`, each on
  the project's folder within its repository (spec-0368).
- regeneration/fence's `unclassified-paths` and its paths stage (ED-07):
  every committed top-level path of the project's folder, and every path
  directly under `dhf/`, denied (`:clean-room :deny`, `:implementation true`
  denying every `:implementation` path) or kept (`:keep`), else one
  finding naming it, no line (G-07 for "committed").
- The tasks component's entries, the rows of cut, scopes, fence and verify
  exactly as spec-0176 writes them, and `run` handing each to its
  component with its arguments and the project's folder.
- `wasm/test.sh` extended to the feature files named for this build's new
  components (`cut`, `scopes`, `fence`, `verify`, in every slice).

## Scenarios it satisfies

- `@spec-0296` A committed path neither denied nor kept fails the check, `fence.feature`
- `@spec-0297` A path under dhf/ is classified on its own, `fence.feature`

2 scenarios, verified in cycle 7. With the paths stage built, check no
longer warns `stage-not-built: paths`, and every scenario of any slice that
runs check meets it: a fixture that commits a path its configuration does
not classify now has a finding. spec-0090 (a skeleton nobody touched) stays
green: init's skeleton keeps every path it writes (spec-0086) and has no
commit.

## Components and interfaces it touches

regeneration/cut (`regeneration-cut`, its helpers; `run` as a shell),
regeneration/fence (`paths-stage`, `unclassified-paths`; `run` as a shell),
regeneration/scopes and verification/verify as shells, the tasks component,
the composition (`slice-06.wac`, `rdm-spec.wac`).

## Done when

- `wasm-tools component wit dhf/wit` parses; each crate's component targets
  its world; `wac compose` writes the slice, and the command with the lines
  below.
- `sh wasm/rdm-spec check` in a project set up by `rdm-spec init` exits 0,
  its error stream no longer naming `paths` as not built; `sh wasm/rdm-spec
  help` still lists every task as spec-0176 writes it.
- regeneration/cut's unit tests cover a cut resolved and refused, a tree at
  a cut and in the index, and `extract` of an executable file, a link, a
  file with an export-subst attribute and nothing ignored (ED-03);
  regeneration/fence's cover a path denied, kept and neither, top-level
  and under `dhf/`.
- `wasm/test.sh` writes a passing result for each scenario above, and the
  scenarios of slices 01 to 05 and 08 still pass.

## Lines for rdm-spec.wac, as text

cut and fence, on their own (ED-01), after `let plugins = ...;` and slice
04's and 03's instances, before slice 02's:

    let cut = new rdm:regeneration-cut { ... };

    let fence = new rdm:regeneration-fence {
        "rdm:spec/regeneration-cut@0.1.0": cut["rdm:spec/regeneration-cut@0.1.0"],
        "rdm:spec/kernel-plugins@0.1.0": plugins["rdm:spec/kernel-plugins@0.1.0"],
        "rdm:spec/architecture-model@0.1.0": model-the-architecture["rdm:spec/architecture-model@0.1.0"],
        ...
    };

Inside slice 02's arguments, beside the other stages' and ahead of the
spread:

    paths-stage: fence["paths-stage"],

The slice's instance, after slice 05's (`let verify-by-results = ...;`),
before `let start-a-project = ...;`:

    let regenerate = new rdm:regenerate {
        "rdm:spec/regeneration-cut@0.1.0": cut["rdm:spec/regeneration-cut@0.1.0"],
        "rdm:spec/regeneration-fence@0.1.0": fence["rdm:spec/regeneration-fence@0.1.0"],
        "rdm:spec/architecture-model@0.1.0": model-the-architecture["rdm:spec/architecture-model@0.1.0"],
        "rdm:spec/events@0.1.0": write-the-spec["rdm:spec/events@0.1.0"],
        "rdm:spec/spec-parser@0.1.0": write-the-spec["rdm:spec/spec-parser@0.1.0"],
        "rdm:spec/verification-results@0.1.0": verify-by-results["rdm:spec/verification-results@0.1.0"],
        "rdm:spec/kernel-plugins@0.1.0": plugins["rdm:spec/kernel-plugins@0.1.0"],
        ...
    };

Inside `start-a-project`'s arguments, above `...not-built,`:

    regenerate: regenerate["rdm:spec/tasks@0.1.0"],
