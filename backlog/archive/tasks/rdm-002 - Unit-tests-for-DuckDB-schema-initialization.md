---
id: RDM-002
title: Unit tests for DuckDB schema initialization
status: To Do
assignee: []
created_date: '2026-03-10 09:20'
labels:
  - github-sync
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add unit tests for init_db() in sync.py. Verify all three tables (github_prs, github_issues, sync_meta) are created with correct column types. Test idempotent schema creation (calling init_db twice). No mocking needed - uses real in-memory DuckDB.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tests verify all 3 tables exist after init_db()
- [ ] #2 Tests verify column schemas match expected types
- [ ] #3 Tests verify idempotent creation (no error on double init)
- [ ] #4 Tests use pytest.importorskip('duckdb')
<!-- AC:END -->
