---
id: RDM-001
title: Verify and stabilize GitHub Project Sync
status: To Do
assignee: []
created_date: '2026-03-10 09:23'
labels:
  - github-sync
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The bidirectional GitHub sync feature (rdm/project_management/sync.py) was cherry-picked onto feature/github-project-sync. It needs bug fixes and comprehensive test coverage before merging to main.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 extract_data import bug fixed - push path uses correct function from backlog_parser
- [ ] #2 Hardcoded master branch reference supports main (and is configurable)
- [ ] #3 Unit tests pass for all sync functions (init_db, pull_prs, graphql, push_features, push_stories, CLI)
- [ ] #4 Integration tests gated on GH_API_TOKEN run against archived repo
- [ ] #5 All tests in tests/pm_sync_test.py pass with pytest
- [ ] #6 ruff lint passes on modified files
<!-- AC:END -->
