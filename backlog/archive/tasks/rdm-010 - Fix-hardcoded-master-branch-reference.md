---
id: RDM-010
title: Fix hardcoded master branch reference
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
sync.py line 77 checks pull_request.base.ref == 'master' to determine if a PR is a valid change. This should support 'main' as the default branch (and ideally be configurable). The existing github.py has the same issue on line 77.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 sync.py supports both main and master as default branch
- [ ] #2 github.py _is_change() updated to support main branch
- [ ] #3 Default branch is configurable via config
- [ ] #4 Existing tests updated to cover main branch
<!-- AC:END -->
