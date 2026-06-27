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

## RDM develops itself with RDM (dogfood)

RDM's own development is governed by RDM's record-first design controls. RDM is
the product under control; its DHF lives in `dhf/` (see `dhf/README.md`). When
changing RDM, you are working inside that DHF's scope:

- **The record** — one `kind: design` document per bounded context under
  `dhf/documents/design/` (record, gating, verification, validation, rendering),
  each owning its `design_inputs`; user needs in the V&V plan; the design review
  in `dhf/documents/design_review.md`; faithfulness verdicts in
  `dhf/faithfulness/`. Never hand-edit the traceability matrix — it is generated.
- **Acceptance criteria are tests** — each design input DI-n is verified by a
  test tagged `@allure.story("DI-n")` in `tests/acceptance/`. Add/changing a
  design input means adding/adjusting its tagged test ("live BDD").
- **Local gate** — install the pre-commit hook so implementation commits are
  blocked unless the design docs are approved (committed):

  ```bash
  uv run rdm hooks .githooks && git config core.hooksPath .githooks
  ```

- **CI enforcement** — `.github/workflows/design-controls.yml` runs the full
  pipeline on every push/PR: design-gate → acceptance tests (Allure) → verify →
  faithfulness → release-gate. A change that leaves a DI unverified, breaks the
  design gate, or edits a tagged test without re-recording its faithfulness
  verdict (goes **stale**) fails CI.
- **After editing a tagged test**, re-record its faithfulness verdict (an
  independent reviewer, the `test-faithfulness` skill, or `rdm story verdict`) —
  the hash-pin intentionally re-opens the §820.30(e) review on any test change.

Run the gates locally exactly as CI does:

```bash
uv run rdm story design-gate --dhf dhf
uv run pytest tests/acceptance --alluredir=dhf/allure-results
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
uv run rdm story faithfulness --dhf dhf
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

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
