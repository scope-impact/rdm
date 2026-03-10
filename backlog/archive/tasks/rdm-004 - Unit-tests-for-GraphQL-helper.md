---
id: RDM-004
title: Unit tests for GraphQL helper
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
Add unit tests for graphql() function by patching urllib.request. Verify correct request formation, token in headers, error handling returns None, and variables default to empty dict.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tests verify successful query returns parsed dict
- [ ] #2 Tests verify network error returns None
- [ ] #3 Tests verify Bearer token in Authorization header
- [ ] #4 Tests verify variables default to empty dict when None
<!-- AC:END -->
