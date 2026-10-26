# The stack

How rdm-spec is built, kept out of the spec on purpose: the spec says what
rdm-spec does, this says what it is made of (Löyly keeps the same split: its
docs never name a stack, and stack choices live beside the implementation;
loyly:docs/wow/invariants.md@17d68d2, INV-2). Decided in
`dhf/decisions/adr-0002-spec-first-regeneration.md`.

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
