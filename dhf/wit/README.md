# The architecture in WIT

The component model's interface language, used as the language of rdm-spec's
architecture in its WebAssembly build (`dhf/decisions/adr-0006-wasm-components.md`).
It names the stack, so it lives here, beside the spec, never in it.

| File | What it holds |
|---|---|
| `deps/rdm-host/host.wit` | package `rdm:host` (its own folder: two packages cannot share one): what the native host gives the core beyond WASI (processes) |
| `events.wit` | package `rdm:spec`: one record per event of `dhf/spec/events.edn` |
| `<context>-<component>.wit` | one interface per component of `dhf/spec/model.edn` |
| `tasks.wit` | `tasks`, the interface every slice's composition exports: its rows of the task table and `run` for its tasks (ADR 0006, "How the command reaches the slices"); `later-slices`, the command's import of each later slice's `tasks` |
| `slice-NN.wit` | one world per slice: exports its home components' interfaces, imports what it uses |
| `rdm-spec.wit` | the world of the whole command: WASI's command world and `rdm:host`; and `not-built`, the stand-in whose tasks answer for every slice not built yet |
| `slice-NN.wac` | the slice composed: its components instantiated, their imports wired to each other, to `rdm:host` and to WASI |
| `rdm-spec.wac` | the command composed from the slices: a new slice adds its instance and one wiring line, rewriting none; the stand-in fills the rest |

Each slice's interfaces and world are written in its plan phase
(`docs/wow/harness.md`, "The planner"), held to the model and the event
catalogue: a component's interface carries the component's name, a record
carries an event's name and its carried keys, nothing the model does not hold.
