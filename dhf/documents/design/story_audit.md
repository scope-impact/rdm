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
---

# Story audit — Software Design

## Design Inputs

This context owns the traceability-integrity requirements, refining UN-007:

- **DI-13 (ID-conflict detection)** — a requirement ID defined in two files is
  reported as a conflict; a uniquely-defined ID is not.
- **DI-14 (definition provenance)** — every ID definition is located with file +
  line, and conflict detection flags *definitions*, not references to an ID.

## Design Outputs

`rdm story audit` / `rdm story check-ids` and `rdm/story_audit/`:

- `check_ids.py` — `find_id_definitions` (id → line), `check_for_duplicates`
  across a file set, `story_check_ids_command`.
- `audit.py` — `scan_*` collectors, `detect_conflicts` (multi-file definitions),
  `run_audit` / `print_report` traceability report (`StoryReference`,
  `AuditResult`).

These operate on the requirement IDs in the repo (the record's integrity), as
distinct from the planning-side `backlog-validate` / `sync` tooling (fenced as
non-record, DI-6). Acceptance criteria are verified by
`@allure.story("DI-13" / "DI-14")` tests; `story_audit_test.py` remains as
lower-level coverage.
