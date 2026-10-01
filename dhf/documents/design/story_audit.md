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
  - id: DI-32
    text: "RDM shall mark the legacy YAML requirements workflow as deprecated: story validate and story check-ids emit a deprecation notice directing users to the record-first model, while remaining functional with unchanged exit codes."
    traces_to: [UN-007]
  - id: DI-37
    text: "RDM shall give each design input a content fingerprint (a short SHA-256 of its whitespace-normalized text) reported in the verification data and the trace; rdm story lock shall write a design-input lock file recording every declared id's fingerprint and retaining removed ids as retired; and, when the lock file is present, the design gate shall fail on a declared design input whose fingerprint differs from its locked fingerprint (reworded without re-locking) or whose id is retired, and warn on a declared id not yet locked."
    traces_to: [UN-007]
---

# Story audit — Software Design

## Design Inputs

This context owns the traceability-integrity requirements, refining UN-007:

- **DI-13 (ID-conflict detection)** — a requirement ID defined in two files is
  reported as a conflict; a uniquely-defined ID is not.
- **DI-14 (definition provenance)** — every ID definition is located with file +
  line, and conflict detection flags *definitions*, not references to an ID.
- **DI-32 (legacy path deprecation)** — the pre-record-first YAML requirements
  workflow (`story validate`, `story check-ids` over `requirements/`) is
  deprecated in favor of the DHF + gates: both commands now print a
  deprecation notice naming the replacement while remaining functional with
  unchanged exit codes — existing users keep working, new users are steered
  to one traceability model instead of one and a half. Refines UN-007.
- **DI-23 (record-first audit)** — on a repository whose record is a DHF
  (record-first model), `rdm story audit` must not report a legacy-only score
  that ignores the actual requirements. When a DHF is present, the audit
  additionally reports each design input's test-tag coverage (does a test
  tagged `@allure.story("DI-n")` exist in the test suite?), lists untagged
  design inputs under stories-without-coverage, and counts them in the
  traceability score — so an unverified design input degrades the grade
  instead of being invisible. (Executed pass/fail stays the release gate's
  job; the audit checks static linkage.) The test-suite scan is anchored to
  the repository that contains the DHF — never the invoking process's working
  directory — so auditing another checkout cannot import the caller's own
  test tags as that repository's coverage.
- **DI-37 (content anchor + lock)** — a `DI-n` id says *which* requirement, not
  *what it said*. Each design input gets a content fingerprint
  (`sha256:` + 12 hex of its whitespace-normalized text), shown in
  `verification.yml` rows and in `rdm story trace`, so a released matrix pins
  the exact wording it verified. `rdm story lock` writes
  `dhf/design_inputs.lock.json` — every declared id's fingerprint, plus
  `retired` for ids no longer declared (kept forever). When the lock exists,
  the design gate fails on a reworded input whose fingerprint no longer
  matches the lock (re-run `rdm story lock` and commit: the lock diff is the
  visible acknowledgment of the rewording) and on a retired id declared again
  (an id must never be reused for a different requirement); a declared id not
  yet in the lock is a warning. Borrowed from the Phoenix architecture's
  content-addressed identity. Refines UN-007.

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

- For **DI-37** — `rdm/record/anchor.py` (`fingerprint`, `write_lock`,
  `lock_findings`); `rdm story lock`; the design gate's lock check
  (`check_design_input_lock` in `design_gate.py`); fingerprints added to
  `verify.py` rows and `build_trace`.

These operate on the requirement IDs in the repo (the record's integrity), as
distinct from the planning-side `backlog-validate` / `sync` tooling (fenced as
non-record, DI-6). Acceptance criteria are verified by
`@allure.story("DI-13" / "DI-14" / "DI-23")` tests; `story_audit_test.py`
remains as lower-level coverage.
