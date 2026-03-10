---
id: RDM-003
title: Unit tests for PR pull logic
status: To Do
assignee: []
created_date: '2026-03-10 09:20'
labels:
  - github-sync
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add unit tests for pull_prs() with mocked PyGithub objects. Verify PRs are correctly inserted into DuckDB, upsert behavior works, edge cases like None body, empty labels, and reviewer filtering are handled.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Mock PR factory fixture created for reusable test objects
- [ ] #2 Tests verify PR insertion with correct field mapping
- [ ] #3 Tests verify upsert replaces existing PR (no duplicates)
- [ ] #4 Tests verify body=None stored as empty string
- [ ] #5 Tests verify labels stored as DuckDB array
- [ ] #6 Tests verify only APPROVED reviewers included
- [ ] #7 Tests verify merged_at=None for open PRs
<!-- AC:END -->
