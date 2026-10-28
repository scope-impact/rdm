# The stack

How rdm-spec is built, kept out of the spec on purpose: the spec says what
rdm-spec does, this says what it is made of (Löyly keeps the same split: its
docs never name a stack, and stack choices live beside the implementation;
loyly:docs/wow/invariants.md@17d68d2, INV-2). Decided in
`dhf/decisions/adr-0002-spec-first-regeneration.md`.

## The WebAssembly build (ADR 0006), growing slice by slice in `wasm/`

| What | Choice |
|---|---|
| Language and target | Rust (stable, 1.97), compiled for `wasm32-wasip2`: every crate but the host is a WebAssembly component on WASI 0.2 |
| Architecture language | WIT, in `dhf/wit/`: package `rdm:spec` (events, one interface per component, one world per slice, the command's world) and `rdm:host` (`host.wit`); bindings by `wit-bindgen` |
| Crates | a Cargo workspace in `wasm/`: one crate per component, `wasm/components/<context>-<component>/`, exporting its interface; `wasm/host/`, the native host |
| Composition | `wac` plugs the components into one command component, `wasm/build/rdm-spec.wasm`, exporting `wasi:cli/run` |
| Host | `wasm/host/`, native Rust embedding `wasmtime` (with `wasmtime-wasi`): preopens the project's folder (and a fixture's), passes the environment and standard streams, implements `rdm:host/process`; no rdm-spec logic |
| Entry point | `wasm/rdm-spec`, a shell script that builds nothing and runs the host on `wasm/build/rdm-spec.wasm` with its arguments |
| Builder's tests | `wasm/test.sh`: builds, runs the unit tests (`cargo test`, natively where a crate allows) and the scenarios of the slices built so far, driving `wasm/rdm-spec` through its commands from the feature files, and writes one Allure result per scenario (and outline row) to `$RESULTS_DIR`, under the spec's README's results contract |
| Tools installed | `cargo`/`rustc` 1.97, target `wasm32-wasip2`; `wasm-tools` 1.227.1, `wac` 0.6.1, `wasmtime` 30.0.2 (CLI) in `~/.local/bin`; crates from crates.io |
| Output | EDN on standard output and in every record, as the spec states |

## The installed release (Clojure), the judge until the switch

| What | Choice |
|---|---|
| Language and runtime | Clojure, run by Babashka; Babashka's built-in libraries only, no other dependency |
| Entry point | `rdm-spec`, a Babashka program; tasks dispatched by `rdm-spec.kernel.command` |
| Namespaces and files | `rdm-spec.<context>.<component>` in `src/rdm_spec/<context>/<component>.clj` (`-` written `_`); kernel in `src/rdm_spec/kernel/` |
| Builder's tests | `test.clj` runs every test: unit tests under `test/` and the scenarios, writing Allure results to `$RESULTS_DIR` |
| Runner's step API | `rdm-spec.kernel.runner`: `(defstep #"pattern" (fn [ctx & captured] ctx'))`; `(attach! name content)` or `(attach! name content type)` attaches evidence to the running step (type `text/plain` by default); an assertion that does not hold is Clojure's `assert` or `(fail! message)`, and every other error is broken |
| Version control | git; the cut is a git tag; the pre-commit hook is `.githooks/pre-commit` with `core.hooksPath` set to `.githooks` |
| CI workflow | GitHub Actions, `.github/workflows/cut.yml` |
| Diagram source and render | Mermaid C4 (`dhf/views/<key>.mmd`), rendered to SVG (`dhf/views/<key>.svg`) by `mmdr` (mermaid-rs-renderer) |
| Site builder | MkDocs, `mkdocs build --strict -f site-src/mkdocs.yml`; publish writes the Markdown and `mkdocs.yml` under `site-src/`, HTML goes to `site/` |
| Audit copy PDFs | Pandoc writes Typst (`pandoc -s -t typst`, with a main font given, `-V mainfont="DejaVu Sans"`, as Pandoc's own template names none; `#set document(date: none)` first, so the same input gives the same bytes) and `typst compile IN OUT` compiles it; the text of a PDF is read with `pdftotext` |
| Oracle artifact | An OCI artifact holding `oracle.edn` (media type `application/edn`, artifact type `application/vnd.scope-impact.rdm-spec.oracle.v1`), stored and fetched by digest with ORAS: `oras pull REPOSITORY@DIGEST`, or `oras pull --oci-layout "$ORACLE_LAYOUT@DIGEST"` for a local OCI layout; `oras push` stores a new one |
| Test results | Allure results (JSON), the `spec` label carrying the scenario id |
| Cycle analysis | Python with numpy, scipy and PyYAML, in its own environment (`/root/.local/share/analysis-venv`), for `dhf/cycles/analysis/posterior.py`; it reads the record through bb, and the integrator's `labels.yaml` and `decisions.yaml` directly. An analysis of the cycles, never part of the implementation |
| Plugin templates | `plugins/` in this repository, carried by every release: `rdm-spec plugin add NAME` copies `plugins/NAME/` (its `template.edn` names the version and the `:plugins` entry to print). Run from sources, rdm-spec reads them from `plugins/` beside `src/`; a package carries them as classpath resources under `rdm-spec/plugins/` (the way of working's packaging step puts them there). Events reach a plugin as EDN on its standard input |
