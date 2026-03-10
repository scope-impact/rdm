---
id: RDM-002
title: Sync Backlog.md milestones to GitHub Projects and Milestones
status: Done
assignee: []
created_date: '2026-03-10 09:25'
updated_date: '2026-03-10 13:24'
labels:
  - github-sync
milestone: m-1
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Push Backlog.md milestones as GitHub Milestones and optionally as GitHub Projects v2 boards. Each milestone maps to a GitHub Milestone for tracking, and its associated tasks are added to a Project board for kanban-style visibility.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Milestones created as GitHub Milestones with title and description
- [x] #2 Existing milestones reused (not duplicated)
- [x] #3 GitHub Project v2 board created per milestone when --projects flag set
- [x] #4 Tasks within milestone added to corresponding Project board
- [x] #5 Milestone status (active/completed/planned) mapped to GitHub milestone state
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Milestone sync implemented as part of push_tasks() in RDM-001. Milestones create GitHub Milestones and optionally Projects v2 boards. Tasks added to corresponding boards.
<!-- SECTION:NOTES:END -->
