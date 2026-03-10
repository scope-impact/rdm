---
id: RDM-008
title: Unit tests for CLI entry point pm_sync_command
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
Add unit tests for pm_sync_command() and CLI arg parsing. Verify error codes for missing dependencies, flag routing (pull-only, push-only, both), status command, env var fallbacks, and sync_meta updates.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tests verify --status works without GitHub token
- [ ] #2 Tests verify missing duckdb/PyGithub/repo/token returns exit code 1
- [ ] #3 Tests verify default (no flags) runs both pull and push
- [ ] #4 Tests verify --pull only skips push
- [ ] #5 Tests verify --push only skips pull
- [ ] #6 Tests verify sync_meta.last_sync updated after run
- [ ] #7 Tests verify repo read from GITHUB_REPOSITORY env var
- [ ] #8 Tests verify CLI arg parsing for pm sync subcommand
<!-- AC:END -->
