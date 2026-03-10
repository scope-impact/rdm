---
id: RDM-005
title: Unit tests for GitHub Projects v2 creation
status: To Do
assignee: []
created_date: '2026-03-10 09:21'
labels:
  - github-sync
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add unit tests for get_or_create_project() and add_issue_to_project() by patching the graphql() function. Verify project lookup, creation flow (4 GraphQL calls), error handling, and matching by epic_id not title.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tests verify existing project ID returned when found
- [ ] #2 Tests verify new project created when not found (create mutation called)
- [ ] #3 Tests verify None returned on API errors
- [ ] #4 Tests verify matching uses epic_id not epic title
- [ ] #5 Tests verify add_issue_to_project returns item ID on success
- [ ] #6 Tests verify add_issue_to_project returns None on failure
<!-- AC:END -->
