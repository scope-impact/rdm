# Ticket 01: The slice composed: the bus, and check's plugins stage wired

Slice: `08-plugins` (`plan.md`, Scope In: "The bus: rdm-spec's own events,
shown and never varying"; "Plugins: ... subscribers"). Build: the component
build of ADR 0006 (`dhf/decisions/adr-0006-wasm-components.md`, cycle 5 of
its build order), its stack in the first section of `dhf/harness/stack.md`.
Architecture: `dhf/wit/` (`kernel-bus.wit`, `kernel-plugins.wit`,
`measurement-oracle.wit`, `slice-08.wit`, `slice-08.wac`; `tasks.wit` and
`spec-check.wit` as slices 01 and 02 left them). Builds on slices 01, 02, 04
and 03 as built.

## What it delivers

The whole path through the slice before any plugin is refused or reports:

- `wasm/components/kernel-bus/`, `kernel-plugins/`, `measurement-oracle/`
  and the tasks component (`plugins-tasks`, ED-04), each building to its own
  component that targets its world (`kernel-bus-component`,
  `kernel-plugins-component`, `measurement-oracle-component`,
  `plugins-tasks`), each world and `plugins` given the host's standard
  interfaces as ED-10 settles. In this ticket the oracle answers that there
  is no oracle to run, and plugin add refuses every name; tickets 04 and 05
  fill them.
- The composition: `wac compose dhf/wit/slice-08.wac` into
  `wasm/build/deps/rdm/plugins.wasm`, and the command with the slice added.
  `rdm-spec.wac`, as cycles 2 to 4 leave it, gains these lines and rewrites
  none (ADR 0006, "How the command reaches the slices"), and its comment's
  "Slices built" gains 08: the slice's instance, above slice 02's (an
  instance is declared before it is used),

      let plugins = new rdm:plugins { ... };

  the stage wiring line inside slice 02's arguments, beside slices 03's and
  04's and ahead of the spread,

      plugins-stage: plugins["plugins-stage"],

  and the tasks wiring line inside the command's arguments, above the
  spread,

      plugins: plugins["rdm:spec/tasks@0.1.0"],

  so that slice 02's instance reads

      let write-the-spec = new rdm:write-the-spec {
          model-lint-stage: model-the-architecture["model-lint-stage"],
          views-stage: model-the-architecture["views-stage"],
          sources-stage: ground-the-spec["sources-stage"],
          wiki-stage: ground-the-spec["wiki-stage"],
          plugins-stage: plugins["plugins-stage"],
          ...not-built,
          ...
      };

  The stand-in's spread stays: the clauses and paths stages, and slices 05,
  06, 07, 09 and 10, are still its. `rdm-spec.wit` changes only in its
  comment: "Slices built" gains `08, plugins (slice-08.wit)`. A later slice
  that imports `kernel-plugins` (05, 06) wires it in its own instance line,
  `"rdm:spec/kernel-plugins@0.1.0": plugins["rdm:spec/kernel-plugins@0.1.0"],`
  (ED-03). The planner composed exactly this against stand-in components
  built from the declarations: the slice alone; the command with slices 01,
  02, 03, 04 and 08 (the not-built stand-in with `include
  check-stages-not-built;`); and that command with a stand-in later slice
  importing `kernel-plugins`.
- kernel/bus: `catalogue`, rdm-spec's own six events, built in, never a
  project's (spec-0303, spec-0304); `route`, each event held to the
  catalogue (a refusal is cannot-run, the glossary's "Event bus"), then the
  deliveries in G-01's order.
- kernel/plugins: `plugins-stage.run`, given check's events in the order
  published: the configuration's `:plugins` read at the project's root
  (`declared`, whole in ticket 02), each subscriber given each event it
  subscribes to, routed by the bus; each run through `rdm:host/process`
  (ED-08): the event's EDN on its standard input, as check hands it, in the
  project's root, with RDM_SPEC (ED-07). It answers no finding yet but what
  ticket 03 adds, publishes nothing, reports nothing.
- `wasm/rdm-spec` sets RDM_SPEC as ED-07 settles.
- `wasm/test.sh` extended to the feature files named for this build's new
  components (`plugins`, `bus`, `oracle`, in every slice).

## Scenarios it satisfies

- `@spec-0166` A task run with --events prints each event it published, in order, `bus.feature`
- `@spec-0167` The same inputs publish the same events in the same order, `bus.feature`
- `@spec-0303` A project whose catalogue declares only its own events passes check, `bus.feature`
- `@spec-0304` A project's catalogue never changes the events a run publishes (3 rows), `bus.feature`
- `@spec-0351` A subscriber is given the event on its standard input, `plugins.feature`
- `@spec-0353` A plugin subscribing to an event a task does not publish does not run in that task, `plugins.feature`

6 scenarios, verified in cycle 5. spec-0303's fixture is a project whose
model holds the component that publishes OrderPlaced, so the model's lint
(slice 04) has nothing to say of its catalogue.

## Components and interfaces it touches

kernel/bus (`kernel-bus`), kernel/plugins (`plugins-stage`, `kernel-plugins`
as a shell), measurement/oracle as a shell, the tasks component, the
composition (`slice-08.wac`, `rdm-spec.wac`), the entry point
`wasm/rdm-spec`; spec/check's stages as slice 02 built them, unchanged.

## Done when

- `wasm-tools component wit dhf/wit` parses; each crate's component targets
  its world; `wac compose` writes the slice, and the command with the lines
  above.
- `sh wasm/rdm-spec check` in a project set up by `rdm-spec init` exits 0,
  its error stream no longer naming `plugins` as not built;
  `sh wasm/rdm-spec check --events` lists KnowledgeIndexed, SpecParsed and
  SpecGated, each with its publisher; `sh wasm/rdm-spec help` still lists
  every task as spec-0176 writes it.
- kernel/bus' unit tests cover the catalogue's six events and each refusal
  of `route`; kernel/plugins' cover the input a subscriber is given.
- `wasm/test.sh` writes a passing result for each scenario above, and slices
  01's, 02's, 03's and 04's scenarios still pass.
