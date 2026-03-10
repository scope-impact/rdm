---
id: RDM-009
title: Integration tests with real GitHub API
status: To Do
assignee: []
created_date: '2026-03-10 09:21'
labels:
  - github-sync
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add integration tests gated on GH_API_TOKEN env var using the archived python/exceptiongroups repo. Test real PR pull, idempotency, and GraphQL queries. Push tests marked @pytest.mark.destructive and gated on TEST_GITHUB_REPO.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tests gated on GH_API_TOKEN (skip if not set)
- [ ] #2 Tests pull PRs from archived repo and verify expected counts
- [ ] #3 Tests verify pull idempotency (twice = same row count)
- [ ] #4 Push tests marked @pytest.mark.destructive
- [ ] #5 Push tests gated on separate TEST_GITHUB_REPO env var
<!-- AC:END -->
