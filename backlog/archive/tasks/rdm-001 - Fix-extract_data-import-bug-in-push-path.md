---
id: RDM-001
title: Fix extract_data import bug in push path
status: To Do
assignee: []
created_date: '2026-03-10 09:20'
labels:
  - github-sync
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The push path in sync.py imports extract_data from rdm.story_audit.sync but that function does not exist. The --push command always fails silently because the ImportError is caught by a generic except block. Fix by importing the correct function (likely extract_backlog_data from backlog_parser) or implementing extract_data.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Push path imports a function that actually exists
- [ ] #2 Push path no longer fails silently on import
- [ ] #3 Error handling distinguishes import errors from runtime errors
<!-- AC:END -->
