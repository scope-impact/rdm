---
id: SDS-SPEC-001
kind: design
context: specification
# Implements part of inputs other contexts own.
realises: [DI-3, DI-18, DI-44, DI-50, DI-61, DI-70]
design_inputs:
  - id: DI-1
    text: "RDM shall read the user-need registry and the design inputs that trace to it from frontmatter and ingest Allure results, with no project-management dependency."
    traces_to: [UN-001, UN-004]
  - id: DI-31
    text: "RDM shall discover verification tags in non-Python test sources — JavaScript/TypeScript allure.story calls and Java @Story annotations across conventional test-file names — so tag-linkage warnings and verification work in polyglot repositories; only the story names a design input."
    traces_to: [UN-004]
  - id: DI-40
    text: "RDM shall read a Python test file's verification tags only from allure story decorators on its test functions and classes and from a module-level pytestmark — never from strings or comments — so a test that writes fixture files is not counted as verifying the ids those files name; a file that does not parse falls back to the decorator pattern. Only the story names a design input: feature and epic carry the bounded context and the user needs."
    traces_to: [UN-004]
  - id: DI-2
    text: "RDM shall block the transition into implementation until design input and review are present, complete, and approved (committed) in git; a later edit re-opens the gate."
    traces_to: [UN-002]
  - id: DI-26
    text: "rdm hooks shall install only the design-gate pre-commit hook by default, adding the issue-reference hooks solely when requested via an explicit flag."
    traces_to: [UN-002]
  - id: DI-46
    text: "The design gate shall fail when a user-need or design-input id is declared more than once across the DHF — twice in one document or in several — naming every document that declares it; the graph shall record how many times each user need and design input is declared, and its shapes shall report a repeated declaration as a violation."
    traces_to: [UN-007]
  - id: DI-15
    text: "RDM shall scaffold a new documentation project from one command, laying down the document templates, build Makefile, and render config."
    traces_to: [UN-008]
  - id: DI-22
    text: "RDM shall scaffold a new design input: allocate the next unused DI id, insert the entry into the chosen context's design_inputs frontmatter, emit a stub acceptance test tagged with the new id that fails until implemented, and print the remaining traceability checklist, rejecting an unknown context or user need."
    traces_to: [UN-010]
  - id: DI-24
    text: "RDM shall bring an existing repository under design controls from one command: lay down the DHF skeleton (V&V plan, per-context design template, design review, traceability matrix), the agent workflow runbook, the design-gate pre-commit hook, a session bootstrap, and a CI gate workflow, skipping (never overwriting) any destination file that already exists."
    traces_to: [UN-011]
  - id: DI-5
    text: "RDM shall classify AI-persona simulated-use runs into a per-user-need formative status (clean / issues / failed / not_run)."
    traces_to: [UN-005]
  - id: DI-33
    text: "RDM shall ingest per-user-need validation records (user need, disposition, reviewer) from the DHF's validation directory and report, at the release gate, each user need lacking an approved validation record as a warning that does not block release."
    traces_to: [UN-005]
---

# Design specification — Software Design

> **Interim (Design Review 30).** This context was formed from `record`, `gating`, `scaffolding`, `validation`. Its
> design inputs moved here unchanged; the prose below is carried over verbatim
> from those documents, by section, until it is rewritten for this context.

## Design Inputs

The core context: user needs, design inputs, tagged tests, the design review and the design gate that guards them, and the commands that create the record (`init`, `adopt`, `new-input`, `hooks`).

- **DI-1 (record ingest)** — read the user-need registry and the design
  inputs (`design_inputs`, each with the needs it `traces_to`) from
  frontmatter, and ingest executed Allure results,
  without depending on any project-management tool. Refines UN-001 and UN-004.
- **DI-31 (polyglot tag discovery)** — executed verification (Allure results)
  was always language-agnostic; source-tag scanning was Python-only, so a
  TypeScript or Java product got no authoring-time linkage warnings and no
  audit coverage. Tag discovery now also reads
  JavaScript/TypeScript `allure.story(...)`/`allure.feature(...)` calls and
  Java `@Story(...)`/`@Feature(...)` annotations across conventional test-file
  names (`*.test.*` / `*.spec.*` / `*Test.java` / `*_test.go` …). Refines
  UN-004.
- **DI-40 (Python tags from decorators only)** — Python tags were read with a
  text pattern, so a test that writes a fixture file containing
  `@allure.story("DI-1")` was counted as verifying DI-1 — ten such false links
  in RDM's own suite. Python tags are now read from the syntax tree: allure
  `story`/`feature` decorators on functions and classes, and a module-level
  `pytestmark`. A file that does not parse falls back to the pattern. Refines
  UN-004. Amended (Design Review 15): only `story` names a design input;
  `feature` and `epic` carry the context and the user needs (DI-57).
- **DI-2 (design gate)** — block the transition into implementation until the
  per-context design documents and the design review are present, complete, and
  approved (committed) in git; a later edit re-opens the gate. Refines UN-002.
- **DI-26 (design-gate-only hooks default)** — `rdm hooks` installs only the
  design-gate pre-commit hook by default; the legacy issue-reference hooks
  (commit-msg / prepare-commit-msg) are installed only with
  `--with-issue-hooks`. RDM's own repo deleted them; downstream defaults
  should match. Refines UN-002.
- **DI-46 (duplicate ids)** — a user need or design input declared twice
  is two requirements wearing one name: the record reader keeps the first,
  so the second silently drops out of every gate and the graph. The design
  gate now fails on it, naming each declaring document, and the graph
  carries each id's declaration count so `rdm graph validate` reports the
  same thing. This replaces the retired `rdm story audit` ID-conflict scan
  (DI-13, DI-14), which searched every file for id-shaped strings rather
  than reading the record. Refines UN-007.
- **DI-15 (project scaffolding)**, refining UN-008: `rdm init` lays down a
  complete starting project — the document templates, the build `Makefile`, and
  the render `config.yml` — so a regulatory author starts from a working
  skeleton rather than a blank repo. The scaffolded V&V plan carries the same
  `user_needs` registry frontmatter the adopt path (DI-24) lays down, so an
  init-scaffolded project speaks the record-first model from day one instead
  of discovering at gate time that its registry has no home.
- **DI-22 (design-input scaffolding)**, refining UN-010: `rdm story new-input`
  guides a contributor (human or agent) through authoring a *traced* design
  input rather than a loose one — it allocates the next unused DI id across the
  whole DHF, inserts the `{id, text, traces_to}` entry into the chosen context's
  `design_inputs` frontmatter, emits a stub acceptance test tagged
  `@allure.story("DI-n")` that **fails until implemented** (so the release gate
  stays honestly red), and prints the remaining traceability checklist (design
  prose, commit-approval, implementation, real assertions, gates, matrix). The
  user needs named by `--traces-to` go on the input itself; there is no
  context-level list to keep in step.
  The requirement text is embedded safely wherever it lands (YAML frontmatter,
  the stub's docstring): quotes, backslashes, or a triple-quote in the text must
  not corrupt the document or the generated test. An unknown context or user need is
  rejected — a design input can never be scaffolded outside the record.
- **DI-24 (brownfield adoption)**, refining UN-011: `rdm adopt` brings an
  *existing* repository under record-first design controls from one command —
  where `rdm init` (DI-15) creates a new documentation project, `rdm adopt`
  drops only the **control surface** into a repo that already has code: the DHF
  skeleton (V&V plan with a `user_needs` registry to fill, a per-context
  `kind: design` template, the design review, the traceability-matrix
  template), the agent workflow runbook, the design-gate pre-commit hook
  (`.githooks/`), a session bootstrap (`.claude/settings.json` +
  `scripts/agent-bootstrap.sh`), and a CI gate workflow. Existing files are
  **skipped, never overwritten** — adoption must not disturb the repository it
  is protecting, and re-running is safe (idempotent). The laid-down templates
  deliberately carry unresolved placeholder markers: the design gate stays red
  until the adopting team writes and commits its actual record.
- **DI-5 (formative validation)** — classify AI-persona simulated-use runs
  into a per-user-need formative status. Refines UN-005.
- **DI-33 (summative validation records)** — audit finding NC-1: the V&V plan
  declares summative validation per user need but the record held no
  validation artifacts and the gate was silent about it. Summative validation
  records now have a home (`<dhf>/validation/UN-…-validation.json`: user
  need, disposition, reviewer) and the release gate names every user need
  lacking an approved record — as a **warning**, because validation is
  human-evidenced and its absence must be visible without pretending a
  machine can supply it. Refines UN-005.

## Design Inputs (context notes) — from `record`

This context owns the design inputs declared in the frontmatter:

## Design Outputs — from `record`

Ingests the system of record so the rest of RDM can compile and gate the DHF.

- `rdm/record/sdd.py` — discover per-context design documents (`kind: design`);
  read the user-need registry (`user_needs`) and the design inputs
  (`design_inputs`) from frontmatter. Which needs a context serves follows
  from its design inputs' `traces_to`; it is not declared separately.
- `rdm/record/allure.py` — parse an Allure results directory into per-design-input
  executed status; scan test sources for the tags they claim (Python from the
  syntax tree, other languages by pattern).
- `rdm/record/verify.py` — build the verification data the DHF renders from.

The layer is dependency-light: no pydantic, DuckDB or RDF dependency.
Retired (Design Review 11): DI-6 — RDM ships no planning tooling, so there
are no planning outputs to mark. Acceptance criteria are verified by
`@allure.story("DI-1")` tests.

## Design Inputs (context notes) — from `gating`

This context owns:

Retired (Design Review 4): DI-19, DI-20, DI-21, DI-27 and DI-28 — the
faithfulness gate, verdict recorder, mutation probe, replayable probes and
verdict hash scope. Independent confirmation that a test means something is
the human-reviewed pull request, not a per-input verdict. These ids are not
reused.

Retired (Design Review 11), with the planning tooling and the story-audit
context: DI-6 (planning outputs marked non-record — RDM ships no planning
tooling), DI-13 and DI-14 (repo-wide ID-conflict scan — replaced by DI-46),
DI-23 (design inputs in the repo audit — the graph and its shapes report
untagged inputs and stray tags) and DI-32 (deprecation notices on the legacy
YAML workflow, now removed). These ids are not reused.

## Design Outputs — from `gating`

Enforces design controls and verified coverage.

- **Design gate** (`rdm/gates/design_gate.py`) — the per-context design
  documents and the review must be present, free of placeholders, and approved
  (committed clean) in git; an edit to an approved document re-opens the gate.
- **Pre-commit hook** (`rdm/hook_files/pre-commit`) — blocks committing
  implementation work until the design gate passes; commits of the design docs
  themselves are allowed (that commit is the approval).
- **Release gate** (`run_release_gate`) — blocks release unless every design
  input is verified by a passing test and every user need is addressed.

Acceptance criteria are verified by `@allure.story("DI-2" / "DI-3" / "DI-26" / "DI-46")`
tests.

## Design Inputs (context notes) — from `scaffolding`

This context owns:

## Design Outputs — from `scaffolding`

For **DI-22** — `rdm story new-input` and `rdm/gates/new_input.py`:

- reuses the record ingest layer (`rdm/record/sdd.py`: `find_design_docs`,
  `design_input_ids`, `registry_user_needs`, `context_of`) so the scaffolder and
  the gates share one view of the DHF;
- inserts the frontmatter entry by targeted line edit (never a YAML re-dump), so
  hand-authored formatting and comments in the design doc survive;
- `--list` prints the discovery inventory (contexts, existing DI ids, next free
  id, user needs) read-only.

For **DI-24** — `rdm adopt` and `rdm/adopt.py`:

- `rdm/adopt_files/` — the packaged control-surface tree, mirroring destination
  paths (`dhf/…`, `.claude/settings.json`, `scripts/agent-bootstrap.sh`,
  `.github/workflows/design-controls.yml`); the pre-commit hook is copied from
  `rdm/hook_files/pre-commit` at adopt time so the gate has one source of truth
  (only the design gate is installed — the issue-reference hooks stay opt-in).
- `adopt(target)` walks the tree: creates missing files (preserving the
  executable bit on scripts/hooks), records and reports every skipped
  pre-existing path, and prints the next steps (fill the templates, commit the
  record, wire `core.hooksPath`).

For **DI-15** — `rdm init` and `rdm/init.py`:

- `init(output_directory)` — copies the packaged `rdm/init_files/` tree into a
  new project directory (templates, `Makefile`, `config.yml`, Dockerfile, pandoc
  configs, `data/`, `images/`).
- `rdm/init_files/documents/` — the shipped templates, including the
  design-controls set (the `kind: design` document template, `design_review.md`,
  `traceability_matrix.md`) so new projects inherit the record-first model.

Acceptance criterion is verified by `@allure.story("DI-15")` — the scaffold lays
down the expected files. The heavier end-to-end check (the scaffold *builds* a
release and passes the gap checklists) is covered by `fresh_release_test.py`,
which runs `make` + `rdm gap` and needs Pandoc, so it stays in the main suite
rather than the design-controls job.

For **DI-63** — the reusable CI:

- `.github/workflows/gates.yml` (`on: workflow_call`) — checks out the caller,
  then RDM at the required `rdm-ref` input (a reusable workflow cannot see
  the ref it was called at, so the caller writes it twice), installs RDM,
  `pytest` and `allure-pytest` from that checkout, runs the caller's
  `install-command` and `test-command`, then the gates action from the same
  checkout, and uploads the Allure report and the verification record.
- `actions/gates/action.yml` — the gate steps, written once: installs
  `rdm[graph]` from `$GITHUB_ACTION_PATH/../..` (unless `install-rdm: false`),
  then the design gate, `verify` and the evidence bundle (only when there are
  Allure results), the release gate (`release-gate`) and graph validation
  (`graph-validate`, `checklists`).
- `action.yml` — the image tag defaults to the action's ref without its `v`
  when the ref is a release, else `latest`.
- `rdm/adopt.py` substitutes the installed version into
  `rdm/adopt_files/.github/workflows/design-controls.yml`, which starts with
  `acceptance-tests: false` and `release-gate: false`.

Acceptance criterion is verified by `@allure.story("DI-63")` in
`tests/acceptance/test_reusable_ci.py`. The test runs the gates action's own
steps against a committed record (passing, failing, phased), checks that every
`rdm` command in the workflow and the actions parses with RDM's CLI, runs the
PDF action's tag logic with a stub `docker`, and reads the adopted workflow and
RDM's own.

## Design Inputs (context notes) — from `validation`

This context owns:

> **Design property (not a design input):** this evidence is *formative only* and
> **never gates release** — the persona reconciler is structurally absent from
> the release gate (a negative/structural property, not mutation-testable; see
> the gating context, where the release gate consults only verification).

## Design Outputs — from `validation`

Exercises UI usability formatively against a user need.

- `.claude/skills/usability-persona/` — a Claude skill that drives a web UI as a
  represented persona (Playwright), logs friction, and emits a `*-persona.json`
  evidence record via `scripts/write_evidence.py`.
- `rdm/record/persona.py` + `rdm story persona` — ingest those runs into
  per-user-need formative status (clean / issues / failed / not_run).
- `rdm/record/validation.py` (DI-33) — parse `validation/UN-…-validation.json`
  records; `run_release_gate` reports user needs without an approved record as
  warnings.

This evidence is **formative only** — it is not summative IEC 62366 validation
and never gates release; the human summative study remains the validation record.
Acceptance criteria are verified by `@allure.story("DI-5")` tests. Note the
persona ingest stays **user-need keyed** — validation is anchored on the user
need, not the design input.

## Components (C3)

The components of the `specification` context, drawn from the architecture
workspace (`rdm c4 draw`).

![Components: specification](../../c4/views/C3_specification.svg)
