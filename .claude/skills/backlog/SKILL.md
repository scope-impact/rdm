---
name: backlog
description: |
  Manage project tasks using Backlog.md, the markdown-based project management
  tool for AI-driven development. Supports CLI and MCP integration.

  **Invoke when:**
  - Creating new tasks or features
  - Breaking down large features into atomic tasks
  - Editing or refining existing tasks
  - Running the spec-driven workflow (PRD -> Tasks -> Plan -> Execute)
  - Managing task status, priorities, and dependencies

  **Provides:**
  - Spec-driven development workflow (4 phases)
  - CLI and MCP commands for task management
  - Task creation guidelines (atomic, testable)
  - Post-processing conventions (user story format, AC splitting)
  - Quality checks for task completeness
---

# Backlog.md Task Management

Manage project tasks using [Backlog.md](https://backlog.md) by Alex Gavrilescu. Tasks are stored as Markdown files with YAML frontmatter, version-controlled with git, readable by both humans and AI agents.

## Quick Start

```bash
# Initialize a new project
backlog init

# Check project's task prefix
cat backlog/config.yml | grep task_prefix

# List all tasks
backlog task list --plain

# Create a task
backlog task create "Task title" -d "Description" --ac "AC1,AC2" -l label1

# Search tasks
backlog search "auth" --status "In Progress"

# View task details
backlog task <id> --plain

# Edit task
backlog task edit <id> -s "In Progress"
```

---

## Spec-Driven Development Workflow

Backlog.md follows a 4-phase workflow. Each phase has a human review checkpoint.

### Phase 1: PRD (Product Requirements Document)

Tell the agent your idea. It writes a PRD markdown file.

```
You --tell idea--> Agent --creates--> PRD.md
```

### Phase 2: Task Creation

Give the agent the PRD. It decomposes into tasks via Backlog.md.

```
You --give PRD--> Agent --task create--> Backlog.md --writes--> Markdown files
```

The agent reads the MCP task creation guide, searches for existing tasks (avoid duplicates), then creates tasks and subtasks.

### Phase 3: Implementation Plan

**Do this right before starting implementation, NOT at task creation time** -- avoids unnecessary conflicts when earlier tasks change requirements.

```
Agent reads task -> Researches codebase -> Writes implementation plan -> Human reviews
```

For multiple tasks, spawn a sub-agent per task for parallel planning:

```
Given the tasks you just created:
1. Spawn a sub-agent for each task and ask them to come up with the
   implementation plan according to the Backlog.md workflow
2. Review the implementation plans and approve them when OK
3. When all tasks have an implementation plan, let me know for final review
```

### Phase 4: Task Execution

Agent implements code following the approved plan. Human reviews code and results.

**Key principle: One task per context window, one PR.** If you run out of context, split the task smaller. Reference other tasks via implementation notes instead of carrying full context.

---

## MCP Integration (Recommended)

MCP is preferred over CLI -- commands are structured so agents cannot hallucinate parameters.

When you run `backlog init`, select **"via MCP connector"** (recommended for Claude Code, Codex, Cursor, Gemini).

### 4 MCP Resources

| Resource | Purpose |
|----------|---------|
| **Workflow overview** | General info about Backlog.md and when to use the next 3 workflows |
| **Task creation guide** | How to create and manage tasks |
| **Task execution guide** | How to execute tasks (read before implementing) |
| **Task completion guide** | How to mark tasks done |

These are loaded on demand (pointers, not full content) to preserve context window.

### MCP Tools

- `task_search` -- search before creating to avoid duplicates
- `task_details` -- get full task information
- `task_create` -- create tasks with structured fields (status dropdowns, etc.)
- `task_update` -- update task fields

---

## CLI Commands

**IMPORTANT:** Use `--plain` flag for AI-friendly output. Check `config.yml` for `task_prefix` before creating tasks.

| Action | Command |
|--------|---------|
| Init project | `backlog init` |
| Create task | `backlog task create "Title" -d "Desc" --ac "AC1,AC2" -l label` |
| Create with plan | `backlog task create "Feature" --plan "1. Research\n2. Implement"` |
| Create with deps | `backlog task create "Feature" --dep task-1,task-2` |
| Create with priority | `backlog task create "Feature" --priority high` |
| Create with DoD | `backlog task create "Feature" --dod "Tests pass" --dod "Lint clean"` |
| Create subtask | `backlog task create -p {task_prefix}-009 "Subtask title"` |
| List tasks | `backlog task list --plain` |
| List by status | `backlog task list -s "In Progress" --plain` |
| List by parent | `backlog task list -p task-42 --plain` |
| View task | `backlog task <id> --plain` |
| Edit task | `backlog task edit <id> -s "Status" -l label` |
| Add AC | `backlog task edit <id> --ac "New criterion"` |
| Add notes | `backlog task edit <id> --notes "Progress update"` |
| Search tasks | `backlog search "auth"` |
| Filter search | `backlog search "api" --status "In Progress"` |
| Append notes | `backlog task edit <id> --append-notes "Update"` |
| Archive | `backlog task archive <id>` |
| Create draft | `backlog task create "Feature" --draft` |
| Promote draft | `backlog draft promote 3.1` |
| Create decision | `backlog decision create "ADR title"` |
| List milestones | `backlog milestone list --plain` |
| TUI board | `backlog` (opens terminal Kanban board) |
| Web UI | `backlog browser` (opens browser interface) |
| Overview | `backlog overview` (project stats) |

**Use FULL parent task ID for subtasks:**

```bash
# CORRECT
backlog task create -p hh-infra-009 "Subtask title"

# WRONG - creates task-9.01 instead of hh-infra-009.01
backlog task create -p 9 "Subtask title"
```

---

## Directory Structure

```
backlog/
├── config.yml                # Project config (project_id, task_prefix, project_name)
├── milestones/               # Milestones (m-N - Title.md)
├── tasks/                    # Tasks ({task_prefix}-NNN - Title.md)
├── completed/                # Archived completed tasks (also parsed by rdm)
├── decisions/                # ADRs (decision-NNN - Title.md)
└── docs/                     # Documentation
    ├── risks/                # Risk clusters (**/*RC-*.md)
    ├── sdd/                  # Software Design Documents
    └── api/                  # API specifications
```

### config.yml

The `config.yml` serves both the backlog CLI and rdm parser. The CLI generates a subset of fields; rdm needs additional fields for traceability.

**CLI generates** (`backlog init`):

```yaml
project_name: "TestProject"
task_prefix: "ft"
default_status: "To Do"
statuses: ["To Do", "In Progress", "Done"]
labels: []
auto_commit: false
remote_operations: true
# ... plus other CLI settings
```

**Add these fields for rdm compatibility** (post-processing):

```yaml
# --- Add to config.yml after `backlog init` ---
project_id: "hhi"                               # Unique project identifier
description: "AWS multi-account infrastructure"  # Project description
repository: "scope-impact/halla-health-infra"    # GitHub repository
```

**Fields parsed by rdm:** `project_id`, `task_prefix`, `project_name`, `description`, `repository`, `labels`. All other fields (`statuses`, `default_port`, `auto_commit`, `remote_operations`, `zero_padded_ids`, etc.) are backlog CLI settings — rdm ignores them via `extra="allow"`.

> **Important:** `project_id`, `task_prefix`, and `project_name` are required by rdm. If `project_id` is missing, rdm derives it from `repository` (e.g., `halla-health-infra` -> `hhi`). If both are missing, it falls back to `task_prefix`.

---

## Task Anatomy

### What the CLI Generates

Running `backlog task create "Title" -d "Desc" --ac "AC1" --ac "AC2" --priority high -l backend` generates:

```markdown
---
id: FT-1
title: Title
status: To Do
assignee: []
created_date: '2026-03-09 16:06'
labels:
  - backend
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Desc
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 AC1
- [ ] #2 AC2
<!-- AC:END -->
```

### Post-Processing for rdm Compatibility

After creating tasks with the CLI, edit the markdown to add rdm-specific sections:

1. **Add `milestone` field** to frontmatter (not generated by CLI): `milestone: m-1`
2. **Add `## Business Value` section** (parsed by rdm, not generated by CLI)
3. **Convert description** to user story format for subtasks
4. **Add `## Subtasks` section** to parent tasks listing subtask IDs

> **Note:** The CLI wraps descriptions in `<!-- SECTION:DESCRIPTION:BEGIN/END -->` markers. The rdm parser automatically strips these, so no manual removal is needed.

### Parent Task (Post-Processed)

```markdown
---
id: FT-042
title: Add GraphQL resolver
status: To Do
assignee: []
created_date: '2026-03-05'
labels: [backend, api]
dependencies: []
milestone: m-1
priority: high
---

## Description

Set up GraphQL resolver for the API layer.

## Business Value

Enables flexible client queries, reducing over-fetching and API round trips.

## Acceptance Criteria

<!-- AC:BEGIN -->
- [ ] #1 Resolver returns correct data for happy path
- [ ] #2 Error response matches REST
- [ ] #3 P95 latency <= 50 ms under 100 RPS
<!-- AC:END -->

## Subtasks

- FT-042.01: Schema definition
- FT-042.02: Resolver implementation

## Implementation Plan

1. Research existing patterns
2. Implement with error handling
3. Add tests
4. Benchmark performance

## Implementation Notes

- Approach taken and technical decisions
- Modified files summary
- Used for PR description
```

### Subtask (Post-Processed)

```markdown
---
id: FT-042.01
title: GraphQL schema definition
status: To Do
assignee: []
created_date: '2026-03-05'
labels: [backend, graphql]
dependencies: []
parent_task_id: FT-042
priority: medium
---

## Description

As a **Backend engineer**, I want to **define the GraphQL schema** so that I can **generate types and validate queries**.

## Acceptance Criteria

<!-- AC:BEGIN -->
- [ ] #1 Schema compiles without errors
- [ ] #2 All entity types defined
<!-- AC:END -->
```

### Valid Field Values

| Field | Valid Values (rdm validation) | CLI supports |
|-------|------------------------------|--------------|
| `status` (task) | `To Do`, `In Progress`, `Done`, `Blocked`, `Cancelled`, `Review`, `In Review` | Any string |
| `status` (milestone) | `active`, `completed`, `planned` | N/A (manual) |
| `status` (decision) | `proposed`, `accepted`, `deprecated`, `superseded` | `proposed` (default) |
| `priority` | `low`, `medium` (default), `high`, `critical` | `low`, `medium`, `high` |

### Frontmatter Fields Parsed by rdm

| Field | Required | Default | CLI generates? |
|-------|----------|---------|----------------|
| `id` | Yes | `""` | Yes |
| `title` | Yes | `""` | Yes |
| `status` | Yes | `"To Do"` | Yes |
| `parent_task_id` | No | `null` | Yes (subtasks) |
| `milestone` | No | `null` | **No** — add manually |
| `priority` | No | `"medium"` | Yes |
| `labels` | No | `[]` | Yes |
| `created_date` | No | `null` | Yes |
| `assignee` | — | — | Yes (ignored by rdm) |
| `dependencies` | — | — | Yes (ignored by rdm) |

> **Note:** The backlog CLI generates `assignee` and `dependencies` fields that rdm ignores (via `extra="allow"` on the schema). For rdm traceability, use implementation notes or AC refs instead of dependencies.

## Milestone Anatomy

Milestones are created manually (no `backlog milestone create` command). Create files in `backlog/milestones/`:

```markdown
---
id: m-1
title: "Platform Foundation"
status: active
created_date: '2026-01-17'
labels: [EP-001, foundation]
---

## Description

Foundation infrastructure for the platform.

## Features

- FT-001: Bootstrap Infrastructure
- FT-002: Networking
- FT-003: Compute Infrastructure
```

Use `backlog milestone list --plain` to view milestones with completion status.

## Decision (ADR) Anatomy

The CLI generates decisions via `backlog decision create "Title"`:

```markdown
---
id: decision-1
title: Use K3s instead of EKS
date: '2026-03-09 16:06'
status: proposed
---
## Context

Why this decision was needed.

## Decision

What was decided.

## Consequences

What follows from this decision.
```

**Post-processing:** Add `labels` to frontmatter and a `## Rationale` section (parsed by rdm but not generated by CLI). Update `status` to `accepted` when approved.

---

### Definition of Done (Global)

Instead of repeating common checks in every task's AC, use the project-level Definition of Done in `config.yml`. Agents must verify DoD items before marking a task done.

Example DoD items:
- `bunx tsc --noEmit` passes when TypeScript touched
- Unit tests pass
- Linting passes

---

## Task Creation Guidelines

For detailed guidelines on writing tasks, post-processing conventions (user story format, AC splitting), task breakdown strategy, and quality checks, see [reference/task_guidelines.md](reference/task_guidelines.md).

Key principles:
- **Atomic**: Single unit of work, single PR scope
- **Testable**: Clear success criteria via acceptance criteria
- **Independent**: No dependencies on future tasks
- **Description = WHY**, not how
- **AC = WHAT** (outcome-oriented, testable, user-focused)

---

## Git Sync

Backlog.md uses git commands under the hood to sync tasks across branches. When you and a colleague work on different branches, updates sync automatically via push/pull. No merge conflicts on task files.

---

## Traceability

Use task IDs in code:

```python
@allure.story("{task_prefix}-042 Add GraphQL resolver")
def test_graphql_resolver(): ...

# Reference specific AC
@trace("{task_prefix}-042:#1", "Happy path resolver")
```

---

## Tips for AI Agents

- **Prefer MCP over CLI** -- structured commands prevent hallucination
- Always use `--plain` flag for CLI list/view commands
- Check `config.yml` for task_prefix before creating
- **Search before creating** to avoid duplicate tasks
- **One task per context window** -- if running out, split smaller
- Reference other tasks via implementation notes, not full context
- Use the Description (why) to guide implementation direction
- Check all AC items before marking done
- Append implementation notes while working for PR descriptions
- Use `--dep` to declare task dependencies

---

## References

- [reference/task_guidelines.md](reference/task_guidelines.md) -- Detailed task creation, post-processing, breakdown strategy
- `docs/SKILL.md` -- DHF documentation guidelines
- `story-audit/reference/id_conventions.md` -- ID format details
