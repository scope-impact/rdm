---
id: SDS-SCAF-001
kind: design
context: scaffolding
design_inputs:
  - id: DI-15
    text: "RDM shall scaffold a new documentation project from one command, laying down the document templates, build Makefile, and render config."
    traces_to: [UN-008]
  - id: DI-22
    text: "RDM shall scaffold a new design input: allocate the next unused DI id, insert the entry into the chosen context's design_inputs frontmatter, emit a stub acceptance test tagged with the new id that fails until implemented, and print the remaining traceability checklist, rejecting an unknown context or user need."
    traces_to: [UN-010]
  - id: DI-24
    text: "RDM shall bring an existing repository under design controls from one command: lay down the DHF skeleton (V&V plan, per-context design template, design review, traceability matrix), the agent workflow runbook, the design-gate pre-commit hook, a session bootstrap, and a CI gate workflow, skipping (never overwriting) any destination file that already exists."
    traces_to: [UN-011]
  - id: DI-63
    text: "RDM shall provide its gates for reuse in another repository's CI: a reusable workflow that runs the repository's acceptance tests and then the design gate, verify, the release gate, graph validation and the evidence bundle, with RDM installed from the revision the caller pinned rather than a package index; a composite gates action that does the same for workflows of their own; and a composite action that renders the documents with the image of the same revision. The CI workflow rdm adopt lays down shall call the reusable workflow pinned to the installed RDM's version."
    traces_to: [UN-011, UN-003]
---

# Scaffolding — Software Design

## Design Inputs

This context owns:

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

- **DI-63 (the gates, reusable from another repository)** — the CI `rdm
  adopt` used to lay down ran `pip install rdm`, which fetches an unrelated
  package of the same name, and every team copied steps that then drifted
  from RDM's own. Now RDM's CI is the reusable unit:
  `scope-impact/rdm/.github/workflows/gates.yml@<ref>` runs the caller's
  acceptance tests, then the design gate, `verify`, the release gate
  (optional while a record has no design input yet), `rdm graph validate`
  (with any checklists) and the evidence bundle, uploaded as an artifact;
  `scope-impact/rdm/actions/gates@<ref>` runs the same gates inside a
  workflow of one's own; `scope-impact/rdm@<ref>` renders the documents in
  the image published for that revision. RDM is installed from the pinned
  revision itself — never by name from a package index — so the gates that
  ran are exactly the ones pinned. The workflow `rdm adopt` lays down calls
  the reusable workflow at `v<installed RDM version>`; RDM's own CI calls
  it too, so every push to RDM exercises it. Refines UN-011 and UN-003.

## Design Outputs

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
