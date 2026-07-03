---
id: SDS-AUDIT-001
kind: design
context: story_audit
satisfies: [UN-007]
design_inputs:
  - id: DI-13
    text: "RDM shall detect requirement IDs defined in more than one place across the project's files, and not flag a unique ID as a conflict."
    traces_to: [UN-007]
  - id: DI-14
    text: "RDM shall locate each requirement-ID definition with its file and line, and flag conflicts only for definitions (not mere references)."
    traces_to: [UN-007]
  - id: DI-23
    text: "RDM shall include record-first design inputs in the traceability audit when the repository contains a DHF: report per-design-input test-tag coverage, list untagged design inputs as stories without coverage, and reflect them in the traceability score."
    traces_to: [UN-007]
---

# Story audit — Software Design

## Design Inputs

This context owns the traceability-integrity requirements, refining UN-007:

- **DI-13 (ID-conflict detection)** — a requirement ID defined in two files is
  reported as a conflict; a uniquely-defined ID is not.
- **DI-14 (definition provenance)** — every ID definition is located with file +
  line, and conflict detection flags *definitions*, not references to an ID.
- **DI-23 (record-first audit)** — on a repository whose record is a DHF
  (record-first model), `rdm story audit` must not report a legacy-only score
  that ignores the actual requirements. When a DHF is present, the audit
  additionally reports each design input's test-tag coverage (does a test
  tagged `@allure.story("DI-n")` exist in the test suite?), lists untagged
  design inputs under stories-without-coverage, and counts them in the
  traceability score — so an unverified design input degrades the grade
  instead of being invisible. (Executed pass/fail stays the release gate's
  job; the audit checks static linkage.)

## Design Outputs

`rdm story audit` / `rdm story check-ids` and `rdm/story_audit/`:

- `check_ids.py` — `find_id_definitions` (id → line), `check_for_duplicates`
  across a file set, `story_check_ids_command`.
- `audit.py` — `scan_*` collectors, `detect_conflicts` (multi-file definitions),
  `run_audit` / `print_report` traceability report (`StoryReference`,
  `AuditResult`).
- For **DI-23** — `audit.py` detects `<repo>/dhf` and reuses the record ingest
  layer (`rdm/record/sdd.py` `design_inputs`, `rdm/record/allure.py`
  `scan_source_tags`) so the audit and the gates share one view of the DHF:
  design inputs join the requirements universe, tagged ones count as covered,
  untagged ones appear in the report and lower the coverage/score.

These operate on the requirement IDs in the repo (the record's integrity), as
distinct from the planning-side `backlog-validate` / `sync` tooling (fenced as
non-record, DI-6). Acceptance criteria are verified by
`@allure.story("DI-13" / "DI-14" / "DI-23")` tests; `story_audit_test.py`
remains as lower-level coverage.
