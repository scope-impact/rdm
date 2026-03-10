# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development

```bash
uv sync --all-extras          # Install all dependencies (dev, github, story-audit)
uv run pytest tests            # Run all tests
uv run pytest tests/render_test.py::test_invert_dependencies  # Run single test
uv run ruff check .            # Lint
uv run ruff check --fix .      # Lint with auto-fix
```

Ruff config: line-length 120, rules E/W/F (see `[tool.ruff]` in pyproject.toml).

## Architecture

RDM is a documentation-as-code CLI tool for IEC 62304 medical device software. It generates regulatory documents from Markdown templates + YAML data files.

**Core pipeline**: YAML data + Jinja2 templates → Markdown → PDF/DOCX (via Pandoc/Typst)

### Key modules

- `rdm/main.py` — CLI entry point (`rdm` command). Argparse subcommands dispatch to feature modules.
- `rdm/render.py` — Jinja2 template engine with two-pass rendering, DuckDB query support, and custom filters (`invert_dependencies`, `join_to`, `md_indent`).
- `rdm/gaps.py` — Gap analysis: validates documents against regulatory checklists (IEC 62304, ISO 13485, etc.). Built-in checklists in `rdm/checklists/`.
- `rdm/md_extensions/` — Markdown post-processing: section numbering, vocabulary expansion.
- `rdm/init_files/` — Scaffold templates for `rdm init` (Makefile, config.yml, document templates, Dockerfile).

### Optional modules (extras)

- `rdm/story_audit/` — Requirements traceability (`pip install rdm[story-audit]`):
  - `backlog_parser.py` — Parses Backlog.md markdown files → `BacklogData` Pydantic v2 model
  - `backlog_schema.py` — Models: `BacklogData`, `Task`, `Milestone`, `AcceptanceCriterion`
  - `validate.py`, `audit.py`, `check_ids.py` — Schema validation, traceability audit, duplicate detection
  - `sync.py` — Backlog.md → DuckDB analytics sync
  - `migrations/` — DuckDB schema migrations

- `rdm/project_management/` — GitHub sync (`pip install rdm[github] rdm[story-audit]`):
  - `sync.py` — Bidirectional sync: Backlog.md tasks → GitHub Issues (push), GitHub PRs → DuckDB (pull)
  - `github.py` — Legacy read-only GitHub backend
  - `base.py` — `BaseBackend` ABC

### Data flow for GitHub sync

```
Backlog.md files → backlog_parser.extract_backlog_data() → BacklogData
  → push_tasks() → GitHub Issues (via PyGithub REST API)
  → Milestones/Projects v2 (via GraphQL)

GitHub PRs → pull_prs() → DuckDB github_prs table (with linked task IDs)
```

## Task Management

This project uses [Backlog.md](https://backlog.md) for task management. Tasks live in `backlog/tasks/`. See `AGENTS.md` for full CLI reference.

**Critical rule**: Never edit task markdown files directly. Always use `backlog` CLI commands (`backlog task create`, `backlog task edit`, etc.).

```bash
backlog task list --plain       # List tasks (AI-friendly output)
backlog task <id> --plain       # View task details
backlog task create "Title" -d "Description" --ac "AC1" --ac "AC2"
backlog task edit <id> -s "In Progress"
```

Task prefix for this project: `rdm` (see `backlog/config.yml`).
