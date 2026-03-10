---
id: RDM-007
title: Unit tests for push stories for feature
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
Add unit tests for push_stories_for_feature() with mocked PyGithub and real DuckDB. Verify story issue creation, body format (As a/I want/So that), AC checkboxes, idempotency, ID fallback, and error continuation.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tests verify issue created with user-story label and correct body format
- [ ] #2 Tests verify already-synced stories are skipped
- [ ] #3 Tests verify story_id falls back to id key
- [ ] #4 Tests verify stories with no ID are skipped
- [ ] #5 Tests verify one story failure does not block others
- [ ] #6 Tests verify acceptance criteria rendered as checkboxes
<!-- AC:END -->
