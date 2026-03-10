---
id: RDM-003
title: Sync GitHub PRs and Issues back to DuckDB for traceability
status: Done
assignee: []
created_date: '2026-03-10 09:25'
updated_date: '2026-03-10 13:24'
labels:
  - github-sync
milestone: m-1
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Pull GitHub PRs and Issues into DuckDB so rdm can generate IEC 62304 change history documentation. Link PRs to Backlog.md tasks via commit message and PR body references. This closes the loop between task management and regulatory documentation.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 All PRs (open and closed) pulled into github_prs table
- [x] #2 PRs linked to backlog tasks via #ID references in commits and PR body
- [x] #3 PR reviewers and approval status captured for regulatory audit trail
- [x] #4 Configurable base branch filter (supports main and master)
- [x] #5 Incremental sync - only fetches new/updated PRs since last sync
- [x] #6 rdm pull and rdm pm sync --pull produce consistent traceability data
<!-- AC:END -->
