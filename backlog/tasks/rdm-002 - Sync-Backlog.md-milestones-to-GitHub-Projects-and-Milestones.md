---
id: RDM-002
title: Sync Backlog.md milestones to GitHub Projects and Milestones
status: Done
assignee: []
created_date: '2026-03-10 09:25'
updated_date: '2026-03-10 09:36'
labels:
  - github-sync
dependencies: []
priority: medium
milestone: m-1
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Push Backlog.md milestones as GitHub Milestones and optionally as GitHub Projects v2 boards. Each milestone maps to a GitHub Milestone for tracking, and its associated tasks are added to a Project board for kanban-style visibility.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Milestones created as GitHub Milestones with title and description
- [ ] #2 Existing milestones reused (not duplicated)
- [ ] #3 GitHub Project v2 board created per milestone when --projects flag set
- [ ] #4 Tasks within milestone added to corresponding Project board
- [ ] #5 Milestone status (active/completed/planned) mapped to GitHub milestone state
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Milestone sync implemented as part of push_tasks() in RDM-001. Milestones create GitHub Milestones and optionally Projects v2 boards. Tasks added to corresponding boards.
<!-- SECTION:NOTES:END -->
