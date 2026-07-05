# Change History

## Unreleased

- Findings from an independent review of the agent-era tooling (PR #40),
  all fixed through the design-controls loop:
    - Mutation-probe verdicts discriminate pytest exit codes: only a genuine
      test failure (exit 1) counts as KILLED; a run that errors or collects
      no tests (exit 5 — e.g. a typo'd selector) is reported as an error,
      never as a kill. Probe replay treats a probe that can no longer execute
      as a per-probe gate failure instead of crashing, and runs pytest under
      the current interpreter (`sys.executable`).
    - `rdm gap` again accepts the `[[KEY: annotation]]` idiom the shipped
      `rdm init` templates use, while a longer colon-qualified key still never
      satisfies its prefix.
    - Test-suite discovery for tag scanning anchors to the repository that
      contains the DHF (bounded by its git root) — never the caller's working
      directory, which could import foreign test tags as coverage.
    - `rdm story new-input` edits block-style `satisfies` lists in place
      (never a duplicate key) and escapes the requirement text embedded in
      the generated stub's docstring.
    - `rdm init` now lays down the `user_needs` registry in the V&V plan and
      the `AGENT_WORKFLOW.md` runbook — parity with `rdm adopt`, so an
      init-scaffolded project works with the record-first gates out of the box.
    - The repository-controls drift check (`--check` in `setup.sh` /
      `apply-repo-controls.sh`) compares the live configuration projected
      onto the declared fields for exact equality; jq `contains` subset
      matching could miss changed values.
    - The pre-commit gate's default exclusion is narrowed to `dhf/` and
      `backlog/` — docs build hooks and CI workflows are implementation and
      are gated too.

- Add `rdm adopt`: bring an existing repository under record-first design
  controls from one command (DHF skeleton, agent runbook, design-gate
  pre-commit hook, session bootstrap, CI gates) — never overwrites.
- Add `rdm story new-input`: scaffold a traced design input — next free DI id,
  frontmatter entry in the owning context, failing tagged stub test, checklist.
- Make `rdm story audit` record-first aware: design-input test-tag coverage in
  the report and the score.
- Ship the `part11_document_control` built-in checklist, and hold RDM's own
  record to it (`dhf/documents/document_control.md`, enforced by test).
- Add the canonical change procedure `dhf/AGENT_WORKFLOW.md` and make the local
  design gate active by default (committed `.githooks/`, session bootstrap).
- Add the worked example `examples/github-document-control/` — git as a
  document control system with GitHub as the provider (Part 11, DMR, DHR).
- Docs site: user manual (installation, quickstarts, task guides, CLI
  reference), Mermaid diagrams, and a traceability-matrix evidence page
  generated from a live acceptance run at every build.
- Fix `rdm gap --coverage` with built-in checklist names.
- Faithfulness reviews become continuously verifiable: verdicts can record
  their executed mutation probes (`rdm story verdict --probe`), and
  `rdm story faithfulness --replay` re-executes every recorded killing probe,
  failing if any now survives; `--stale` lists the review worklist.
- Verdict hash scope: module scope by default (editing a shared helper or
  fixture re-opens the review), `--hash-scope function` selectable, and
  pre-existing verdicts honored as function-scoped.
- Sound `rdm gap` reference matching: keys count only inside `[[ … ]]` blocks,
  matched exactly with a dotted-descendant-covers-parent hierarchy rule — a
  bare prose mention or a longer sibling key no longer counts as coverage.
- `rdm story new-input` also adds newly referenced user needs to the owning
  context's `satisfies` list.
- `rdm hooks` installs only the design-gate pre-commit hook by default; the
  issue-reference hooks moved behind `--with-issue-hooks`.
- Polyglot tag discovery: JS/TS allure calls and Java Story/Feature
  annotations are scanned across conventional test-file names; non-Python
  tests pin faithfulness verdicts at whole-file scope.
- Summative validation records (`<dhf>/validation/UN-…-validation.json`);
  the release gate warns for every user need lacking an approved record.
- Part 11 checklist completeness: §11.10(h) device checks and §11.10(j)
  signature accountability added (audit finding NC-4).
- RDM's own repository controls as code: `.github/rulesets/` +
  `scripts/apply-repo-controls.sh [--check]`.
- Mutation-probe writes advance the probed file's mtime to a fresh whole
  second: CPython's (mtime-seconds, size) pyc validation could serve stale
  bytecode to a size-preserving mutation applied within one second, making a
  killing probe falsely SURVIVE under back-to-back `--replay`. Found by an
  independent review; a second independent review then proved the first fix
  (unique-nanosecond mtime bumps) insufficient — the pyc key truncates mtime
  to whole seconds — leading to the whole-second advance.
- The legacy YAML requirements workflow (`story validate`, `check-ids`) is
  deprecated with a notice; functional, exit codes unchanged.
- New `rdm story dmr` (device-master-record index data generated from
  controlled documents' frontmatter) and `rdm story evidence-bundle` (the
  retained release evidence set: verification data, rendered matrix,
  verdicts, manifest).

## v0.11.0

- Replace the `rdm tex` command with heavily customized pandoc calls. This simplifies
  the RDM code and allows for more easier PDF-customization.
- Add support for folders in the documents folder.
- Add support word document generation.
- Remove support for SVGs in the PDF files, which was fragile anyway since the
  SVG-to-PDF conversion is imperfect.
- Start adding a few 510(k)-related documents.
- Ignore all of the release files by default.
- Various improvemnts to the documentation.
- Improve data file organization.
