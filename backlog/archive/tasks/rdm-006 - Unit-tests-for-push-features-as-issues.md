---
id: RDM-006
title: Unit tests for push features as issues
status: To Do
assignee: []
created_date: '2026-03-10 09:21'
labels:
  - github-sync
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add unit tests for push_features_as_issues() with mocked PyGithub and real DuckDB. Verify issue creation format, idempotency (skips already-synced), milestone creation, label merging, GithubException handling, and project integration.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tests verify issue created with [FID] Title format and feature label
- [ ] #2 Tests verify already-synced features are skipped
- [ ] #3 Tests verify milestones created for epics, existing ones reused
- [ ] #4 Tests verify custom labels merged with feature label
- [ ] #5 Tests verify GithubException handled gracefully (no crash)
- [ ] #6 Tests verify no project creation when token is None
- [ ] #7 Tests verify issue body contains source ID
<!-- AC:END -->
