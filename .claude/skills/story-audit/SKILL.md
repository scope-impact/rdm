---
name: story-audit
description: |
  Audit and maintain requirements traceability across code, tests, and documentation.
  Detects ID conflicts, orphan code, and coverage gaps.

  **Invoke when:**
  - Adding new features or user stories
  - Detecting or fixing ID conflicts
  - Checking test-to-story coverage
  - Pre-release traceability review
  - Onboarding to understand requirements structure
  - Auditing compliance with traceability standards

  **Provides:**
  - ID conflict detection across yaml/py/md files
  - Orphan code detection (code without story reference)
  - Test coverage by story
  - Traceability matrix generation
  - ID convention validation
  - Gap analysis and recommendations
  - Deep analytics via DuckDB (epic progress, quality metrics, phase completion)
---

# Story Audit: Requirements Traceability

Audit requirements traceability across your codebase using the `rdm` tool.

## Installation

```bash
# Install rdm with story audit support
pip install rdm[story-audit]

# Or with uv
uv pip install rdm[story-audit]
```

## Quick Commands

| Command | Purpose |
|---------|---------|
| `rdm story audit` | Full traceability audit with score |
| `rdm story validate` | Validate requirements YAML against schema |
| `rdm story sync /path/to/backlog -o out.duckdb` | Sync Backlog.md to DuckDB |
| `rdm story check-ids` | Check for duplicate IDs |
| `rdm story backlog-validate [dir]` | Validate Backlog.md files for consistency |

---

## Full Traceability Audit

Run a comprehensive audit of your codebase:

```bash
# Audit current directory
rdm story audit

# Audit specific repository
rdm story audit /path/to/repo
```

**Output includes:**
- Summary table (IDs in requirements, tests, source)
- ID conflicts found
- Stories without coverage
- Orphan test/source files
- Coverage by prefix (FT, US, EP, etc.)
- Traceability score (0-100) with grade

---

## Schema Validation (Legacy YAML)

> **Note:** For new projects, use Backlog.md format with `rdm story sync` instead.

Validate legacy requirements YAML files against the Pydantic schema:

```bash
# Validate all files in requirements/
rdm story validate

# Validate specific file (legacy YAML)
rdm story validate -f requirements/features/ft-007.yaml

# Strict mode (fail on extra fields)
rdm story validate --strict

# Verbose output (show warnings)
rdm story validate --verbose

# Custom requirements directory
rdm story validate -r /path/to/requirements
```

---

## Duplicate ID Detection

Check for duplicate story IDs (useful in pre-commit hooks):

```bash
# Check requirements directory (legacy YAML)
rdm story check-ids

# Check specific files (for pre-commit)
rdm story check-ids requirements/features/ft-001.yaml requirements/features/ft-002.yaml

# For Backlog.md projects, use sync instead
rdm story sync /path/to/backlog -o backlog.duckdb
```

**Exit codes:**
- `0` - No duplicates found
- `1` - Duplicates found (lists conflicts)

---

## DuckDB Analytics (Backlog.md Sync)

Sync Backlog.md to DuckDB for SQL-based analytics:

```bash
# Sync Backlog.md directory to DuckDB (backlog_dir and -o are required)
rdm story sync /path/to/backlog -o backlog.duckdb

# Run migrations only (create empty schema)
rdm story sync --migrate-only -o backlog.duckdb
```

### Backlog.md Validation

Validate markdown files for consistency with the DuckDB sync schema:

```bash
# Validate entire backlog directory
rdm story backlog-validate /path/to/backlog

# Validate single file
rdm story backlog-validate -f path/to/task.md

# Strict mode (treat warnings as errors)
rdm story backlog-validate --strict

# Verbose (show warnings)
rdm story backlog-validate --verbose
```

### Backlog.md Directory Structure

```
backlog/
├── config.yml           # Project config (required)
├── milestones/          # Milestone files (m-1.md, m-2.md)
├── tasks/               # Active tasks ({task_prefix}-001.md, {task_prefix}-001.01.md)
├── completed/           # Completed tasks
├── decisions/           # ADRs (decision-001 - *.md)
├── docs/
│   ├── risks/           # Risk docs ({task_prefix}-risks-NNN - RC-*.md)
│   ├── sdd/             # Software Design Documents
│   ├── ots/             # Off-the-Shelf components
│   └── api/             # API specifications
└── design-transfer/     # Deployment runbooks
```

### config.yml Format

```yaml
project_id: "hhi"                    # Unique project identifier
task_prefix: "ft"                    # Prefix for task IDs
project_name: "Halla Health Infrastructure"
description: "AWS multi-account infrastructure"
repository: "scope-impact/halla-health-infra"
labels:
  - bootstrap
  - networking
```

### Database Schema (Backlog.md v2.0.0)

| Table | Description |
|-------|-------------|
| `projects` | Project config from config.yml |
| `milestones` | Milestones (formerly epics) |
| `tasks` | Parent tasks (features) |
| `subtasks` | Subtasks (user stories) |
| `acceptance_criteria` | AC items from tasks/subtasks |
| `risks` | Risk documents |
| `risk_controls` | Risk control measures |
| `decisions` | Architecture Decision Records |
| `labels` | Label dimension table |

### Global ID Format

All IDs are prefixed with `project_id:` for cross-project support:
- `hhi:hh-infra-003` (task)
- `hhi:hh-infra-003.01` (subtask)
- `hhi:m-1` (milestone)
- `hhi:hh-infra-risks-003` (risk)

### Query the Database

```bash
# Open DuckDB CLI
duckdb backlog.duckdb

# Summary counts
SELECT 'projects' as t, COUNT(*) as n FROM projects
UNION ALL SELECT 'milestones', COUNT(*) FROM milestones
UNION ALL SELECT 'tasks', COUNT(*) FROM tasks
UNION ALL SELECT 'subtasks', COUNT(*) FROM subtasks
UNION ALL SELECT 'risks', COUNT(*) FROM risks
UNION ALL SELECT 'decisions', COUNT(*) FROM decisions;

# Tasks by status
SELECT status, COUNT(*) as count
FROM tasks
GROUP BY status
ORDER BY count DESC;

# Milestone progress
SELECT
    m.local_id,
    m.title,
    m.status,
    COUNT(t.global_id) as task_count,
    SUM(CASE WHEN t.status = 'Done' THEN 1 ELSE 0 END) as done
FROM milestones m
LEFT JOIN tasks t ON t.milestone_id = m.global_id
GROUP BY m.global_id, m.local_id, m.title, m.status;

# Subtasks per parent task
SELECT
    t.local_id,
    t.title,
    t.status,
    COUNT(s.global_id) as subtask_count,
    SUM(CASE WHEN s.status = 'Done' THEN 1 ELSE 0 END) as done
FROM tasks t
LEFT JOIN subtasks s ON s.parent_task_id = t.global_id
GROUP BY t.global_id, t.local_id, t.title, t.status
ORDER BY t.local_id;

# Acceptance criteria completion
SELECT
    t.local_id,
    t.title,
    t.acceptance_criteria_count,
    t.completed_criteria_count,
    ROUND(t.completed_criteria_count * 100.0 / NULLIF(t.acceptance_criteria_count, 0), 0) as pct
FROM tasks t
WHERE t.acceptance_criteria_count > 0
ORDER BY pct DESC;

# Risks by severity
SELECT severity, COUNT(*) as count
FROM risks
WHERE severity IS NOT NULL
GROUP BY severity
ORDER BY
    CASE severity
        WHEN 'Critical' THEN 1
        WHEN 'High' THEN 2
        WHEN 'Medium' THEN 3
        WHEN 'Low' THEN 4
        ELSE 5
    END;

# Risk mitigation status
SELECT
    mitigation_status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER(), 1) as pct
FROM risks
GROUP BY mitigation_status;
```

### Pre-built Queries

See `scripts/analytics_queries.sql` for comprehensive analytics:

```bash
duckdb backlog.duckdb < .claude/skills/story-audit/scripts/analytics_queries.sql
```

---

## ID Convention

### Task Prefix (from config.yml)

```yaml
# backlog/config.yml
task_prefix: "hh-infra"  # Project-specific: hh-llm, hh-studio, hh-wallet, hh-app
```

### Standard Prefixes

| Prefix | Meaning | Example | Location |
|--------|---------|---------|----------|
| `m-N` | Milestone | `m-1` | `milestones/*.md` |
| `{task_prefix}-NNN` | Task | `hh-infra-003` | `tasks/*.md` |
| `{task_prefix}-NNN.NN` | Subtask | `hh-infra-003.01` | `tasks/*.md` |
| `decision-NNN` | ADR | `decision-001` | `decisions/decision-NNN - *.md` |
| `{task_prefix}-risks-NNN` | Risk Document | `hh-infra-risks-003` | `docs/risks/{task_prefix}-risks-NNN - RC-*.md` |

### Legacy Prefixes (YAML format)

| Prefix | Meaning | Example |
|--------|---------|---------|
| `EP-XXX` | Epic | `EP-001` |
| `FT-XXX` | Feature | `FT-007` |
| `US-XXX` | User Story | `US-042` |
| `RISK-XXX` | Risk | `RISK-IAM-001` |
| `RC-XXX` | Risk Cluster | `RC-IAM` |

---

## Traceability Mechanisms

Use Backlog.md format with `task_prefix` from `config.yml`. See `reference/traceability_patterns.md` for full details.

### 1. Testable Stories -> Allure Tags

```python
# tests/infrastructure/test_compute.py
import allure

@allure.feature("hh-infra-003 Compute Infrastructure")
@allure.story("hh-infra-003.01 K3s cluster EC2")
class TestK3sCluster:
    @allure.severity(allure.severity_level.BLOCKER)
    def test_cluster_running(self) -> None:
        """K3s cluster is operational."""
        ...
```

### 2. Non-Testable Stories -> @trace Decorator

```python
# src/gitops/flux.py
from src.traceability import trace

@trace("hh-infra-004.01", "Flux bootstrap configuration")
def bootstrap_flux(repo_url: str, branch: str) -> bool:
    """Bootstrap Flux CD on the cluster."""
    ...

@trace("hh-infra-004.03")
class GitOpsConfig:
    """GitOps repository structure configuration."""
    ...
```

### 3. Documentation -> Inline References

```markdown
<!-- docs/architecture.md -->
## Compute Architecture

Per **hh-infra-003.01** (K3s cluster EC2), the system provisions...
```

---

## CI Integration

### Pre-commit Hook

```yaml
# .pre-commit-config.yaml
- repo: local
  hooks:
    - id: check-duplicate-ids
      name: Check for duplicate story IDs
      entry: rdm story check-ids
      language: system
      files: \.(yaml|yml)$
      pass_filenames: true
```

### GitHub Action

```yaml
# .github/workflows/traceability.yml
name: Traceability Audit
on: push

jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: astral-sh/setup-uv@v7
      - run: uv sync --all-extras
      - run: uv run rdm story validate --strict
      - run: uv run rdm story check-ids
      - run: uv run rdm story backlog-validate backlog/
```

---

## Schema Reference

### Schema Models

Two schema modules exist:

**`rdm.story_audit.backlog_schema` (v2.0.0)** — Backlog.md markdown format:

| Model | Description | Source |
|-------|-------------|--------|
| `BacklogConfig` | Project configuration (project_id, task_prefix) | config.yml |
| `Milestone` | Milestone/epic | milestones/*.md |
| `Task` | Task or subtask | tasks/*.md, completed/*.md |
| `RiskDoc` | Risk document | docs/*RC-*.md |
| `Decision` | ADR | decisions/*.md |
| `AcceptanceCriterion` | AC checkbox item | Parsed from markdown |
| `BacklogData` | Complete backlog collection | All of the above |

**`rdm.story_audit.schema` (v1.1.0)** — Legacy YAML requirements format:

| Model | Description | Source |
|-------|-------------|--------|
| `RequirementsIndex` | Index file | _index.yaml |
| `Feature` | Feature spec with user stories | features/FT-*.yaml |
| `UserStory` | Individual requirement | Within feature YAML |
| `RiskCluster` | Risk cluster with controls | Risk YAML files |

**Parsing:** `rdm.story_audit.backlog_parser` handles markdown-to-model conversion using `parse_frontmatter()`, shared across both parser and validator.

### Task Markdown Format

```markdown
---
id: ft-003
title: "Compute Infrastructure"
status: In Progress
milestone: m-1
priority: high
labels: [kubernetes, alb]
created_date: '2026-01-17'
---

## Description

Task description here.

## Business Value

Why this matters.

## Acceptance Criteria

<!-- AC:BEGIN -->
- [x] #1 First criterion (completed)
- [ ] #2 Second criterion (pending)
<!-- AC:END -->
```

### Subtask Markdown Format

```markdown
---
id: ft-003.01
title: "K3s cluster on EC2"
status: Done
parent_task_id: ft-003
labels: [kubernetes, k3s]
---

## Description

As a **Platform engineer**, I want to **provision K3s cluster** so that I can **run workloads**.

## Acceptance Criteria

<!-- AC:BEGIN -->
- [x] #1 K3s operational
- [x] #2 kubectl configured
<!-- AC:END -->
```

---

## Common Problems & Fixes

### Problem 1: ID Conflicts

**Symptom:** Same ID used in multiple files.

**Detection:**
```bash
rdm story check-ids
```

**Fix:**
1. Identify the conflict from the output
2. Decide which keeps the ID (usually the original)
3. Reassign conflicting item to new ID
4. Update all references

**Prevention:** Always run `rdm story check-ids` before committing.

### Problem 2: Orphan Tests

**Symptom:** Tests exist but no story reference.

**Detection:**
```bash
rdm story audit  # Shows orphan test files
```

**Fix:**
```python
# Before
class TestValidation:
    def test_valid_input(self):
        ...

# After
@allure.feature("hh-llm-001 Dataset Schema")
@allure.story("hh-llm-001.05 Input Validation")
class TestValidation:
    def test_valid_input(self):
        ...
```

### Problem 3: Stories Without Tests

**Detection:**
```bash
rdm story audit  # Shows "Stories Without Coverage" section
```

**Decision tree:**
```
Is story testable?
+-- YES -> Add test with @allure.story
+-- NO -> Is it process/documentation?
    +-- YES -> Add @trace to implementation
    +-- NO -> Review if story is needed
```

---

## References

- `reference/id_conventions.md` — Full ID schema (Backlog.md + legacy YAML)
- `reference/traceability_patterns.md` — Implementation patterns (@allure, @trace, markdown)
- `scripts/analytics_queries.sql` — Pre-built DuckDB SQL queries

### RDM Source Modules

| Module | Purpose |
|--------|---------|
| `rdm/story_audit/backlog_schema.py` | Pydantic models for Backlog.md (v2.0.0) |
| `rdm/story_audit/backlog_parser.py` | Markdown parser (`parse_frontmatter`, `parse_task`, etc.) |
| `rdm/story_audit/backlog_validate.py` | Backlog validation (uses parser's `parse_frontmatter`) |
| `rdm/story_audit/sync.py` | DuckDB sync (`populate_tables`, migrations) |
| `rdm/story_audit/audit.py` | Traceability audit (scan tests/source/docs) |
| `rdm/story_audit/validate.py` | Legacy YAML validation |
| `rdm/story_audit/schema.py` | Legacy YAML Pydantic models (v1.1.0) |
| `rdm/story_audit/check_ids.py` | Duplicate ID detection |

## Related Skills

- `/backlog` — Create well-structured tasks using the `backlog` CLI
