# The architecture in WIT

The component model's interface language, used as the language of rdm-spec's
architecture in its WebAssembly build (`dhf/decisions/adr-0006-wasm-components.md`).
It names the stack, so it lives here, beside the spec, never in it.

| File | What it holds |
|---|---|
| `host.wit` | package `rdm:host`: what the native host gives the core beyond WASI (processes) |
| `events.wit` | package `rdm:spec`: one record per event of `dhf/spec/events.edn` |
| `<context>-<component>.wit` | one interface per component of `dhf/spec/model.edn` |
| `slice-NN.wit` | one world per slice: exports its home components' interfaces, imports what it uses |
| `rdm-spec.wit` | the world of the whole command: WASI's command world, `rdm:host`, and every slice |

Each slice's interfaces and world are written in its plan phase
(`docs/wow/harness.md`, "The planner"), held to the model and the event
catalogue: a component's interface carries the component's name, a record
carries an event's name and its carried keys, nothing the model does not hold.
