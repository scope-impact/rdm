---
id: RDM-001
title: Sync Backlog.md tasks to GitHub Issues
status: Done
assignee: []
created_date: '2026-03-10 09:25'
updated_date: '2026-03-10 09:36'
labels:
  - github-sync
dependencies: []
priority: high
milestone: m-1
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Push Backlog.md tasks as GitHub Issues with proper label mapping. Currently sync.py tries to import a nonexistent extract_data function from a legacy format. Rewire the push path to read from the Backlog.md parser (backlog_parser.extract_backlog_data) and map tasks, subtasks, and acceptance criteria to GitHub Issues.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tasks pushed as GitHub Issues with title format [TASK-ID] Title
- [ ] #2 Subtasks pushed as linked issues referencing parent issue number
- [ ] #3 Acceptance criteria rendered as checkbox list in issue body
- [ ] #4 Task labels and priority mapped to GitHub labels
- [ ] #5 Already-synced tasks skipped via DuckDB tracking (idempotent)
- [ ] #6 Task status changes reflected in GitHub issue state (open/closed)
<!-- AC:END -->
