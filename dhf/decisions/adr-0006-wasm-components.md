# ADR 0006: rdm-spec rebuilt as WebAssembly components, one slice at a time

> Status: Accepted, 2026-10-28, by the spec owner. The spec does not change:
> what rdm-spec does stays as it is; what it is made of changes
> (`dhf/harness/stack.md`). Builds on ADR 0002 (spec-first regeneration),
> ADR 0004 (slices) and ADR 0005 (slice development).

## Context

rdm-spec is regenerated from its spec; the stack is a choice the spec never
names. It has been Clojure on Babashka since the cutover, and was rebuilt once
in TypeScript (cycle 21) with the instrument held still. The spec owner wants
it rebuilt again, slice by slice, as WebAssembly components: Rust compiled for
WASI 0.2, its boundaries written in WIT, the component model's interface
language, used as the language of the architecture.

Two facts shape the decision. WASI gives a component files, clocks, standard
streams and the environment, but no way to start a process, and rdm-spec runs
git and commands everywhere: cut, scopes, fence, verify's checks and every
plugin. And slice development builds one slice per cycle, so for twelve cycles
there is no whole WebAssembly build to install.

## Decision

- **WIT is the architecture's language.** `dhf/wit/` holds package `rdm:spec`:
  `events.wit` (one record per event of `dhf/spec/events.edn`, its carried
  keys as fields), one interface per component of `dhf/spec/model.edn`
  (`<context>-<component>.wit`), one world per slice (`slice-NN.wit`)
  exporting the interfaces of the components whose home is that slice (the
  slice its model spec path names) and importing those it uses, and
  `rdm-spec.wit`, the world of the whole command. `dhf/wit/host.wit` is
  package `rdm:host`: what the native host gives the core beyond WASI. The WIT
  is written in each slice's plan phase, held to the model by the planner and
  the spec owner, and kept outside `dhf/spec/` because it names the stack.
  Generating it from the model (and checking it is fresh, as the views are)
  is a later spec change, once slice 04 exists in this build.
- **A small native host.** `rdm-spec` is a native Rust program embedding a
  WebAssembly runtime: it gives the core WASI (the project's folder
  preopened, the environment, standard streams, clocks) and `rdm:host/process`
  (run an argument vector in a folder, with an environment, standard input and
  a time limit, and return its exit, output and error). All of rdm-spec's
  logic is in the WebAssembly core; the host holds none, and grows only when
  the core needs a capability WASI lacks.
- **Side by side.** The WebAssembly build lives in `wasm/`: a Cargo
  workspace, one crate per component (`wasm/components/<context>-<component>/`),
  the host (`wasm/host/`), the composition into one component, and
  `wasm/test.sh`, its tests, writing Allure results. Each cycle builds the
  next slice there, in the order of their numbers, verified on the scenarios
  of the components it reaches and graded black box. The Clojure release stays
  installed and is the judge (verify, fence, the oracle's gate) until all
  thirteen slices exist; then the WebAssembly build is installed in one switch,
  in a whole-project cycle.
- **Output stays EDN.** Every result and record the spec states is EDN, as
  the spec says; the stack changes nothing a grader can see.

## Consequences

- Building slice 01 builds its components whole, so `kernel/command` is
  built with it: the task table, `help`, how every task reports. A component a
  slice reaches is built with every scenario it owns, in every slice, so early
  slices carry regression items from later ones; the planner orders tickets so
  each lands once.
- The configuration's verify runs `wasm/test.sh` and `wasm/rdm-spec help`
  from now on: the Clojure source stays in the repository as the installed
  release's source, unverified, until the switch.
- A later spec change makes the WIT generated from the model, and a lint holds
  it to the model and the event catalogue.
