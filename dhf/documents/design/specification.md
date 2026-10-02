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

## Purpose

The core bounded context: the user needs, the design inputs that refine them
(each owned by one context and naming the needs it `traces_to`), the tagged
tests that claim to verify them, the design review, and the design gate that
blocks implementation until the record is complete, approved and declares
every id once. It speaks the glossary's user need, design input, design
document, tagged test, design review, approved and design gate, and, for
validation, validated (validation records, formative persona evidence). Every
other context conforms to its ids. Two things that are not contexts are drawn
here: the shared kernel (Record kernel, Utilities) until it moves to its own
package, and onboarding (`rdm init`, `rdm adopt`), the commands that create
the record, which is this context's application layer.

## Design Inputs

- **DI-1 (record ingest)** — read the user-need registry and the design
  inputs (`design_inputs`, each with the needs it `traces_to`) from
  frontmatter, and ingest executed Allure results, with no
  project-management tool. The registry and the inputs are this context's;
  parsing the results is drawn in `test_evidence`. Refines UN-001 and UN-004.
- **DI-2 (design gate)** — block the transition into implementation until the
  per-context design documents and the design review are present, complete
  and approved (committed) in git; a later edit re-opens the gate. Refines
  UN-002.
- **DI-5 (formative validation)** — classify AI-persona simulated-use runs
  into a per-user-need formative status (`clean`, `issues`, `failed`,
  `not_run`). Keyed by user need, since validation is anchored there; it is
  formative evidence, never a validation record. Refines UN-005.
- **DI-15 (project scaffolding)** — `rdm init` lays down a complete starting
  project: the document templates, the build `Makefile` and the render
  `config.yml`. Its V&V plan carries the same `user_needs` registry that
  `rdm adopt` (DI-24) lays down, so a new project speaks the record-first
  model from day one. Refines UN-008.
- **DI-22 (design-input scaffolding)** — `rdm story new-input` authors a
  traced design input: it allocates the next unused DI id across the DHF,
  inserts `{id, text, traces_to}` into the chosen context's `design_inputs`,
  emits a stub test tagged `@allure.story("DI-n")` that fails until
  implemented, and prints the remaining traceability checklist. The text is
  escaped wherever it lands (frontmatter, the stub's docstring), so a quote,
  backslash or triple-quote cannot corrupt either. An unknown context or user
  need is rejected: no design input is scaffolded outside the record.
  Refines UN-010.
- **DI-24 (brownfield adoption)** — `rdm adopt` brings an existing repository
  under design controls from one command. Where `rdm init` creates a new
  project, `rdm adopt` lays down only the control surface: the DHF skeleton
  (V&V plan with an empty `user_needs` registry, a `kind: design` template,
  the design review, the traceability-matrix template), the agent workflow
  runbook, the design-gate pre-commit hook (`.githooks/`), a session
  bootstrap (`.claude/settings.json`, `scripts/agent-bootstrap.sh`) and a CI
  gate workflow. An existing file is skipped, never overwritten, so a re-run
  is safe. The templates keep their placeholder markers, so the design gate
  stays red until the team writes and commits its own record. Refines UN-011.
- **DI-26 (design-gate-only hooks default)** — `rdm hooks` installs only the
  design-gate pre-commit hook; the issue-reference hooks (`commit-msg`,
  `prepare-commit-msg`) only with `--with-issue-hooks`. Refines UN-002.
- **DI-31 (polyglot tag discovery)** — source-tag scanning was Python-only, so
  a TypeScript or Java product got no authoring-time linkage warnings. It
  also reads JavaScript/TypeScript `allure.story(...)` calls and Java
  `@Story(...)` annotations in conventional test files (`*.test.*`,
  `*.spec.*`, `*Test.java`, `*_test.go`, …). Only the story names a design
  input. A tag is a test's claim on a design input, this context's language,
  not a test run. Refines UN-004.
- **DI-33 (summative validation records)** — audit finding NC-1: the V&V plan
  declared summative validation per user need, but the record held none and
  the gate was silent. Validation records live in
  `<dhf>/validation/UN-…-validation.json` (user need, disposition, reviewer),
  and the release gate names each user need without an approved one — as a
  warning, because validation is a human judgment a machine cannot supply.
  Refines UN-005.
- **DI-40 (Python tags from decorators only)** — a text pattern counted a
  test that writes a fixture containing `@allure.story("DI-1")` as verifying
  DI-1 (ten false links in RDM's own suite). Python tags are read from the
  syntax tree: allure `story` decorators on functions and classes and a
  module-level `pytestmark`; a file that does not parse falls back to the
  pattern. Refines UN-004. Amended (Design Review 15): only `story` names a
  design input; `feature` and `epic` carry the context and the user needs
  (DI-57).
- **DI-46 (duplicate ids)** — an id declared twice is two requirements under
  one name: the reader keeps the first, so the second drops out of every gate
  and the graph. The design gate fails on it, naming each declaring
  document; the graph's declaration count and shape are `graph`'s part. It
  replaces the retired `rdm story audit` id-conflict scan (DI-13, DI-14),
  which searched files for id-shaped strings instead of reading the record.
  Refines UN-007.

Retired (Design Review 4): DI-19, DI-20, DI-21, DI-27 and DI-28 — the
faithfulness gate, verdict recorder, mutation probe, replayable probes and
verdict hash scope. Independent confirmation that a test means something is
the reviewed pull request, not a per-input verdict. These ids are not
reused.

Retired (Design Review 11), with the planning tooling and the story-audit
context: DI-6 (planning outputs marked non-record — RDM ships no planning
tooling), DI-13 and DI-14 (repo-wide id-conflict scan — replaced by DI-46),
DI-23 (design inputs in the repo audit — the graph and its shapes report
untagged inputs and stray tags) and DI-32 (deprecation notices on the legacy
YAML workflow, now removed). These ids are not reused.

## Design Outputs

- **Record kernel** (`sdd.py`, `ids.py`, `git.py`, `reconcile.py` in
  `rdm/record/`) — DI-1, DI-46. `sdd.py` finds design documents by their
  `kind: design` marker, never by file name; reads `user_needs`,
  `design_inputs` and `realises`; keeps an id's first declaration by sorted
  path; and lists every declaration (`duplicate_declarations`). `ids.py` is
  the id grammar, `git.py` the one way to ask git, `reconcile.py` the shared
  bucketing of observations by declared id. No pydantic, DuckDB or RDF
  dependency.
- **Utilities** (`rdm/util.py`) — shared YAML, message and repository-root
  helpers; shared kernel, no input of its own.
- **Design and release gates** (`rdm/gates/design_gate.py`) — DI-2, DI-46.
  The design gate fails unless at least one design document and the design
  review exist, hold no placeholder markers and are committed clean (an
  uncommitted edit re-opens it; outside git, approval is reported as
  unverifiable); and fails on an id declared twice. It warns on a user need
  nothing traces to, an unknown `traces_to` or `realises` id, and a design
  input with no tag (or, with `--allure-results`, no passing run). It also
  fails on stale or uncommitted architecture views: this context's part of
  `architecture`'s DI-70. The module still holds the release gate and the
  trace (`rdm story trace`), which belong to `release`; until they move,
  this context realises part of `release`'s DI-3 and DI-18 and of `risk`'s
  DI-44 and DI-50 (it applies the risk register's `findings`). The release
  gate also gives DI-33's warning.
- **Pre-commit hook** (`rdm/hook_files/pre-commit`) — DI-2. Blocks a commit
  that stages implementation files (source, template or configuration
  extensions outside the DHF and `backlog/`) unless the design gate passes.
  A commit of only the design documents passes — that commit is the
  approval. A missing `rdm` blocks; `RDM_SKIP_DESIGN_GATE=1` bypasses.
- **Hooks installer** (`rdm/hooks.py`) — DI-26. Copies the pre-commit hook
  into `.git/hooks` (or a given directory), the issue-reference hooks only
  with `--with-issue-hooks`.
- **New design input** (`rdm/gates/new_input.py`) — DI-22. Reads the DHF
  through the Record kernel, so it and the gates share one view of the
  record; inserts the entry by a targeted line edit (never a YAML re-dump,
  so comments survive); writes the stub to
  `tests/acceptance/test_<context>.py` unless `--test-file` is given;
  `--list` prints contexts, taken ids, the next id and the user needs.
- **Project scaffold**, **Project templates** (`rdm/init.py`,
  `rdm/init_files/`) — DI-15. Copies the tree (templates including the
  design-controls set, `Makefile`, `config.yml`, Dockerfile, Pandoc and Typst
  configuration, `data/`, `images/`) into a new directory, `dhf` by default,
  and the runbook from the adoption templates, so both scaffolds share one.
- **Adoption**, **Adoption templates** (`rdm/adopt.py`, `rdm/adopt_files/`)
  — DI-24. Creates each missing file of the tree, keeps the hook and the
  bootstrap executable, writes the installed version for `{rdm_version}`,
  reports what it skipped, and prints the next steps. The pre-commit hook is
  copied from `rdm/hook_files/` so the gate has one source. The CI workflow
  it lays down calls `release`'s reusable workflow (DI-63).
- **Validation records** (`rdm/record/validation.py`) — DI-33. Reads
  `<dhf>/validation/*-validation.json` by user need; only `approved` counts.
- **Formative usability** (`rdm/record/persona.py`) — DI-5. Reconciles
  `*-persona.json` runs against the registry: `failed` if any run could not
  finish, else `issues` if problems were seen, else `clean`; `not_run` when
  none tried. `clean` is not validated.
- **Persona command** (`rdm/record/persona_cmd.py`) — DI-5. `rdm story
  persona` prints the status per need from the V&V plan's registry and
  always exits 0 on a successful run. The runs come from the
  `usability-persona` skill in `.claude/skills/`, outside the `rdm` package.
- **Test-tag scanning** (in `rdm/record/allure.py`) — DI-1, DI-31, DI-40.
  The tags belong here, but they share a module with the results ingest, so
  the workspace draws the module as the Allure reader in `test_evidence`
  until `allure.py` is split. `find_tests_dir` finds `tests/` or `test/` no
  higher than the DHF's repository root; `scan_source_tags` maps each story
  id to the files claiming it — Python from the syntax tree, JavaScript,
  TypeScript and Java by pattern, and YAML task `tags`. `scan_source_tests`
  gives `graph` each tagged test by name: this context's part of `graph`'s
  DI-61.

## Components (C3)

![Components: specification](../../c4/views/C3_specification.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Record kernel | Design and V&V frontmatter, ids, git, the shared reconcile helpers | Python | `rdm/record/` |
| Utilities | Shared YAML and file helpers (the shared kernel) | Python | `rdm/util.py` |
| Design and release gates | Design gate and duplicate ids; the release gate and trace until they move to release | Python | `rdm/gates/design_gate.py` |
| Hooks installer | `rdm hooks` | Python | `rdm/hooks.py` |
| Pre-commit hook | Runs the design gate before a commit | shell | `rdm/hook_files/` |
| New design input | `rdm story new-input` | Python | `rdm/gates/new_input.py` |
| Project scaffold | `rdm init` | Python | `rdm/init.py` |
| Project templates | What `rdm init` lays down | Markdown, YAML, Typst | `rdm/init_files/` |
| Adoption | `rdm adopt` | Python | `rdm/adopt.py` |
| Adoption templates | What `rdm adopt` lays down | Markdown, YAML | `rdm/adopt_files/` |
| Validation records | Approved validation records per user need | Python | `rdm/record/validation.py` |
| Formative usability | Persona runs as formative evidence | Python | `rdm/record/persona.py` |
| Persona command | `rdm story persona` | Python | `rdm/record/persona_cmd.py` |

The view also draws the Allure reader (`test_evidence`), the architecture
model (`architecture`) and the risk register (`risk`), which these use.

- The pre-commit hook runs the design gate; the hooks installer installs the
  hook and uses Utilities.
- The design and release gates read the record with the Record kernel, find
  tagged tests and reconcile results with the Allure reader, and check the
  views are fresh with the architecture model; the release gate in them
  reads validation records and applies the risk rules of the risk register.
- New design input reads the design documents with the Record kernel and
  finds the test suite with the Allure reader.
- Project scaffold and adoption copy their templates.
- Persona command classifies runs with formative usability; it, formative
  usability and validation records read through the Record kernel.

Open questions: the Record kernel's code path is all of `rdm/record/`,
which also holds other contexts' components; the view does not show that
`rdm init` copies the runbook from the adoption templates or that
`rdm adopt` copies the pre-commit hook.

### Dynamic view

The order matters for a contributor: git runs the pre-commit hook, which runs
the design gate; the gate reads the design documents and the review, checks
the architecture views are fresh, and finds the tagged tests. Any one failing
stops the commit.

![Scenario: a commit meets the design gate](../../c4/views/D_specification_commit.svg)

## Dependencies

Depends on the shared kernel, which it draws. Depended on by every other
context: `risk` (`risk.py` uses the private `sdd._frontmatter_of`),
`test_evidence` (the pytest plugin reads the design inputs), `release`
(`verify.py`, `bundle.py`), `publishing` (`dmr.py`, `report.py`) and `graph`
(`project.py`). `rdm/main.py`, the composition root, wires its commands.

Imports that break the rule, and what removes them:

- `design_gate.py` → `rdm.record.risk` and the results half of
  `rdm.record.allure` (`reconcile`), for the release gate, the trace and the
  design gate's `--allure-results` warnings. Moving the release gate and the
  trace to `release` removes the risk import; the results warnings must move
  with them, or the results import stays.
- `design_gate.py`, `new_input.py` → the tag scanning in `rdm.record.allure`
  (`scan_source_tags`, `find_tests_dir`): this context's code in a module
  drawn in `test_evidence`. Splitting `allure.py` into tags and results
  removes it.
- `design_gate.py` → `rdm.record.c4` (`architecture`), for view freshness
  (DI-70): upward, though no cycle, and not named in `architecture.md`.
  Letting `architecture` provide the check the design gate calls through the
  kernel, or moving the freshness test into the kernel, removes it.
- `risk.py` → `sdd._frontmatter_of`: right direction, private helper. Moving
  the frontmatter parser to the kernel removes the coupling.

## Out of scope

- Summative validation. Persona evidence is formative only — not summative
  IEC 62366 validation — and never gates release: the release gate does not
  read persona runs (a structural property, not mutation-testable). The human
  summative study is the validation record.
- Planning. RDM ships no planning tooling; tasks live outside the record.
