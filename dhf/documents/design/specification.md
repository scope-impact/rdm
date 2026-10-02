---
id: SDS-SPEC-001
kind: design
context: specification
# Implements part of inputs other contexts own.
realises: [DI-61, DI-70]
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
here: the shared kernel (`rdm/kernel/`), below every context, and onboarding
(`rdm init`, `rdm adopt`), the commands that create the record, which is this
context's application layer.

## Design Outputs

- **Shared kernel** (`rdm/kernel/`) — not a context: the YAML, message and
  repository-root helpers (`util.py`), the id grammar (`ids.py`), the one way
  to ask git (`git.py`), the frontmatter parser (`frontmatter.py`), the
  shared bucketing of observations by declared id (`reconcile.py`) and the
  version. No pydantic, DuckDB or RDF dependency.
- **Record reader** (`rdm/specification/sdd.py`) — DI-1, DI-46. Finds
  design documents by their `kind: design` marker, never by file name; reads
  `user_needs`, `design_inputs` and `realises`; keeps an id's first
  declaration by sorted path; and lists every declaration
  (`duplicate_declarations`).
- **Test tags** (`rdm/specification/tags.py`) — DI-1, DI-31, DI-40.
  `find_tests_dir` finds `tests/` or `test/` no higher than the DHF's
  repository root; `scan_source_tags` maps each story id to the files
  claiming it — Python from the syntax tree, JavaScript, TypeScript and Java
  by pattern, and YAML task `tags`. `scan_source_tests` gives `graph` each
  tagged test by name: this context's part of `graph`'s DI-61.
- **Design gate** (`rdm/specification/design_gate.py`) — DI-2, DI-46. Fails
  unless at least one design document and the design review exist, hold no
  placeholder markers and are committed clean (an uncommitted edit re-opens
  it; outside git, approval is reported as unverifiable); fails on an id
  declared twice; and fails on stale or uncommitted architecture views, this
  context's part of `architecture`'s DI-70. It warns on a user need nothing
  traces to, an unknown `traces_to` or `realises` id, and a design input with
  no tag. With `--allure-results`, the warnings about executed results are
  `release`'s, handed to the gate's output by the composition root: the
  specification never reads results.
- **Pre-commit hook** (`rdm/hook_files/pre-commit`) — DI-2. Blocks a commit
  that stages implementation files (source, template or configuration
  extensions outside the DHF and `backlog/`) unless the design gate passes.
  A commit of only the design documents passes — that commit is the
  approval. A missing `rdm` blocks; `RDM_SKIP_DESIGN_GATE=1` bypasses.
- **Hooks installer** (`rdm/specification/hooks.py`) — DI-26. Copies the pre-commit hook
  into `.git/hooks` (or a given directory), the issue-reference hooks only
  with `--with-issue-hooks`.
- **New design input** (`rdm/specification/new_input.py`) — DI-22. Reads the DHF
  through the record reader, so it and the gates share one view of the
  record; inserts the entry by a targeted line edit (never a YAML re-dump,
  so comments survive); writes the stub to
  `tests/acceptance/test_<context>.py` unless `--test-file` is given;
  `--list` prints contexts, taken ids, the next id and the user needs.
- **Project scaffold**, **Project templates** (`rdm/specification/init.py`,
  `rdm/specification/init_files/`) — DI-15. Copies the tree (templates including the
  design-controls set, `Makefile`, `config.yml`, Dockerfile, Pandoc and Typst
  configuration, `data/`, `images/`) into a new directory, `dhf` by default,
  and the runbook from the adoption templates, so both scaffolds share one.
- **Adoption**, **Adoption templates** (`rdm/specification/adopt.py`, `rdm/specification/adopt_files/`)
  — DI-24. Creates each missing file of the tree, keeps the hook and the
  bootstrap executable, writes the installed version for `{rdm_version}`,
  reports what it skipped, and prints the next steps. The pre-commit hook is
  copied from `rdm/specification/hook_files/` so the gate has one source. The CI workflow
  it lays down calls `release`'s reusable workflow (DI-63).
- **Validation records** (`rdm/specification/validation.py`) — DI-33. Reads
  `<dhf>/validation/*-validation.json` by user need; only `approved` counts.
- **Formative usability** (`rdm/specification/persona.py`) — DI-5. Reconciles
  `*-persona.json` runs against the registry: `failed` if any run could not
  finish, else `issues` if problems were seen, else `clean`; `not_run` when
  none tried. `clean` is not validated.
- **Persona command** (`rdm/specification/persona_cmd.py`) — DI-5. `rdm story
  persona` prints the status per need from the V&V plan's registry and
  always exits 0 on a successful run. The runs come from the
  `usability-persona` skill in `.claude/skills/`, outside the `rdm` package.
## Components (C3)

![Components: specification](../../c4/views/C3_specification.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Shared kernel | Shared helpers every context may use: YAML and files, ids, git, frontmatter, the reconcile helpers | Python | `rdm/kernel/` |
| Record reader | User needs, design inputs, realises and declarations, from the frontmatter | Python | `rdm/specification/sdd.py` |
| Test tags | The design-input tags in test sources, in every language RDM reads | Python | `rdm/specification/tags.py` |
| Design gate | The record complete, approved and declared once; the architecture views fresh | Python | `rdm/specification/design_gate.py` |
| Hooks installer | `rdm hooks` | Python | `rdm/specification/hooks.py` |
| Pre-commit hook | Runs the design gate before a commit | shell | `rdm/specification/hook_files/` |
| New design input | `rdm story new-input` | Python | `rdm/specification/new_input.py` |
| Project scaffold | `rdm init` | Python | `rdm/specification/init.py` |
| Project templates | What `rdm init` lays down | Markdown, YAML, Typst | `rdm/specification/init_files/` |
| Adoption | `rdm adopt` | Python | `rdm/specification/adopt.py` |
| Adoption templates | What `rdm adopt` lays down | Markdown, YAML | `rdm/specification/adopt_files/` |
| Validation records | Approved validation records per user need | Python | `rdm/specification/validation.py` |
| Formative usability | Persona runs as formative evidence | Python | `rdm/specification/persona.py` |
| Persona command | `rdm story persona` | Python | `rdm/specification/persona_cmd.py` |

The view also draws the architecture model (`architecture`), which the design
gate uses, and the components of other contexts that use this one's.

- The pre-commit hook runs the design gate; the hooks installer installs the
  hook.
- The design gate reads the record with the record reader, finds the tagged
  tests with the test tags, and checks the views are fresh with the
  architecture model.
- New design input reads the design documents with the record reader and
  finds the test suite with the test tags.
- Project scaffold and adoption copy their templates; scaffold also copies the
  runbook from the adoption templates, and adoption the pre-commit hook.
- Persona command classifies runs with formative usability and reads the V&V
  plan with the record reader; validation records read the registry with it.

The shared kernel is drawn here but is not this context's: every context may
use it, and the arrows into it are left out of the other views.

### Dynamic view

The order matters for a contributor: git runs the pre-commit hook, which runs
the design gate; the gate reads the design documents and the review, checks
the architecture views are fresh, and finds the tagged tests. Any one failing
stops the commit.

![Scenario: a commit meets the design gate](../../c4/views/D_specification_commit.svg)

## Dependencies

Layer 2 of the dependency rule. Depends on the shared kernel and on the
leaves below it: `architecture` (the design gate's view check). Depended on
by `test_evidence` (the pytest plugin reads the design inputs), `release`
(the release gate, the verification data), `publishing` (the DMR index, the
report, the evidence bundle) and `graph` (the projection). `rdm/main.py`, the
composition root, wires its commands, and hands the design gate `release`'s
results warnings. It reads no results and no risk register.

## Out of scope

- Summative validation. Persona evidence is formative only — not summative
  IEC 62366 validation — and never gates release: the release gate does not
  read persona runs (a structural property, not mutation-testable). The human
  summative study is the validation record.
- Planning. RDM ships no planning tooling; tasks live outside the record.
