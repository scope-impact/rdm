# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development

```bash
uv sync --all-extras          # Install all dependencies (dev, github, story-audit)
uv run pytest tests            # Run all tests
uv run pytest tests/render_test.py::test_invert_dependencies  # Run single test
uv run ruff check .            # Lint
uv run ruff check --fix .      # Lint with auto-fix
uv run --extra docs mkdocs build   # Build the docs site -> site/ (gitignored)
uv run --extra docs mkdocs serve   # Live-preview the docs at localhost:8000
```

Ruff config: line-length 120, rules E/W/F (see `[tool.ruff]` in pyproject.toml).

Documentation is a [MkDocs](https://www.mkdocs.org/) site (Material theme):
the Markdown prose under `docs/` plus an API reference generated from the source
docstrings via [mkdocstrings](https://mkdocstrings.github.io/). Config is
`mkdocs.yml`; nav and the `::: rdm.…` reference pages live in `docs/`. The
rendered site lands in `site/` (gitignored). `mkdocs build --strict` fails on a
broken link or missing nav entry — run it the way CI does.

## RDM develops itself with RDM (dogfood)

RDM's own development is governed by RDM's record-first design controls; its
DHF lives in `dhf/` (see `dhf/README.md`).

**For ANY change, follow `.claude/skills/traceable-change/SKILL.md`** — it is
the operating procedure (classify the change, design record committed first,
tagged acceptance test, gates exactly as CI, independent faithfulness verdict);
this section is only the summary. The headline rules:

- Touching `.py`? The design record (a `kind: design` doc under
  `dhf/documents/design/`, owning its `design_inputs`) must be **committed
  before** the implementation — that commit is the approval, and the pre-commit
  hook enforces it. Each DI-n is verified by a test tagged
  `@allure.story("DI-n")` in `tests/acceptance/` ("live BDD").
- Editing a tagged test makes its faithfulness verdict **stale** — an
  **independent** reviewer (never the test's author; see the
  `test-faithfulness` skill) must re-record it via `rdm story verdict`.
- Never hand-edit the traceability matrix, `dhf/faithfulness/*.json`, or
  `backlog/tasks/*.md`; never bypass the gate (`RDM_SKIP_DESIGN_GATE`,
  `--no-verify`).

Session bootstrap (Claude Code runs it automatically via the SessionStart hook;
other agents/humans run it once by hand): `bash contrib/agent-bootstrap.sh` —
installs deps, points `core.hooksPath` at the committed `.githooks/`, reports
the gate state. CI (`.github/workflows/design-controls.yml`) re-runs the full
gate pipeline on every push/PR as the non-bypassable floor.

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
