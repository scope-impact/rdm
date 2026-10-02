# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development

```bash
uv sync --all-extras          # Install all dependencies (dev, graph, docs, validation)
uv run pytest tests            # Run all tests
uv run pytest tests/render_test.py::test_invert_dependencies  # Run single test
uv run ruff check .            # Lint
uv run ruff check --fix .      # Lint with auto-fix
uv run --extra docs mkdocs build   # Build the docs site -> site/ (gitignored)
uv run --extra docs mkdocs serve   # Live-preview the docs at localhost:8000
```

Ruff config: line-length 120, rules E/W/F (see `[tool.ruff]` in pyproject.toml).

Note: the docs build runs the acceptance suite (a build hook generates the
traceability-matrix evidence page from a live run), so expect `mkdocs build`
to take ~10s and to need the full dev environment for real evidence — it
degrades to a "no data" notice otherwise.

Documentation is a [MkDocs](https://www.mkdocs.org/) site (Material theme):
the Markdown prose under `docs/` plus an API reference generated from the source
docstrings via [mkdocstrings](https://mkdocstrings.github.io/). Config is
`mkdocs.yml`; nav and the `::: rdm.…` reference pages live in `docs/`. The
rendered site lands in `site/` (gitignored). `mkdocs build --strict` fails on a
broken link or missing nav entry — run it the way CI does.

## RDM develops itself with RDM (dogfood)

RDM's own development is governed by RDM's record-first design controls. RDM is
the product under control; its DHF lives in `dhf/` (see `dhf/README.md`).
**`dhf/AGENT_WORKFLOW.md` is the canonical end-to-end procedure** — the
decision tree, the 7-step loop, and the gate-failure fixes. Scaffold a new
design input with `uv run rdm story new-input` (`--list` shows contexts, taken
ids, and user needs). When changing RDM, you are working inside that DHF's
scope:

- **The record** — one `kind: design` document per bounded context under
  `dhf/documents/design/` (one per context; `architecture.md` lists them),
  each owning its `design_inputs`; the risk policy and register in `dhf/documents/risk/`; user needs in the V&V plan; the design review
  in `dhf/documents/design_review.md`. Never hand-edit the traceability
  matrix — it is generated.
- **Design inputs are the acceptance criteria; tests verify them** — a design
  input DI-n is a system or subsystem `shall` requirement: *baseline* when it
  follows from a user need, *risk-based* when a risk's `controls:` allocates it.
  It is verified by a test tagged `@allure.story("DI-n")` in
  `tests/acceptance/`, accepted as a whole. The test's verification steps
  (`with verification_step(...)`) are the test's own checks, never acceptance
  criteria: what must be accepted separately is a separate design input.
  Adding/changing a design input means adding/adjusting its tagged test
  ("live BDD"). "Clause" means a checklist clause only.
- **Vocabulary** — `CONTEXT.md` is the glossary (user need, design input,
  bounded context, verification step, risk control, effective, proposal…).
  Use its terms and avoid the ones it lists under _Avoid_; when a term is
  resolved or changes, update `CONTEXT.md` in the same change (the
  domain-modeling skill).
- **Local gate** — the design-gate pre-commit hook is committed in `.githooks/`
  and activated automatically at session start (`.claude/settings.json` runs
  `scripts/agent-bootstrap.sh`, which also syncs dependencies). Manual
  activation, if ever needed:

  ```bash
  git config core.hooksPath .githooks
  ```

- **CI enforcement** — `.github/workflows/design-controls.yml` runs the full
  pipeline on every push/PR: design-gate → acceptance tests (Allure) → verify →
  release-gate. A change that leaves a DI unverified or breaks the design gate
  fails CI.
- **Independent verification is the pull-request review** — git is the
  controlled record, and a reviewer other than the author approves the PR.
  Whether a tagged test actually proves its design input is that reviewer's
  call; no separate verdict is recorded.

Run the gates locally exactly as CI does:

```bash
uv run rdm story design-gate --dhf dhf
uv run pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

## Architecture

RDM keeps the design record of regulated software (IEC 62304 first) as Markdown and tests in git, in four parts:
**Record** (needs, design inputs, checklists, tagged tests; changed only by a reviewed PR), **Gates** (design gate,
release gate, gap analysis), **Graph** (the record as a read-only RDF graph for people and agents; never edited), and
**Documents** (YAML data + Jinja2 templates → Markdown → PDF/DOCX via Pandoc/Typst). `dhf/documents/architecture.md`
assigns each bounded context to a part. Agent skills live in scope-impact/agent-skills, not here.

### Key modules

- `rdm/main.py` — CLI entry point (`rdm` command). Argparse subcommands dispatch to feature modules.
- `rdm/render.py` — Jinja2 template engine with two-pass rendering and custom filters (`invert_dependencies`, `join_to`, `md_indent`).
- `rdm/gaps.py` — Gap analysis: validates documents against regulatory checklists (IEC 62304, ISO 13485, etc.). Built-in checklists in `rdm/checklists/`.
- `rdm/md_extensions/` — Markdown post-processing: section numbering, vocabulary expansion.
- `rdm/init_files/` — Scaffold templates for `rdm init` (Makefile, config.yml, document templates, Dockerfile).
- `rdm/gates/` — Design gate (including duplicate ids), release gate, `new-input` scaffolding, mutation probe — core, no extra.
- `rdm/record/` — Reads the record: design/V&V frontmatter (`sdd.py`), Allure results (`allure.py`), verification (`verify.py`), the risk register and policy (`risk.py`).
- `rdm/graph/` — The design record projected into RDF (`rdm graph build | query | validate | serve | explorer-file | mcp`, extra `graph`): named graphs per source, embedded Oxigraph store, SHACL gate shapes, SPARQL endpoint for AWS Graph Explorer, and a read-only MCP server for agents (`agent.py`, registered in `.mcp.json`). See `docs/graph.md`.

### Optional extras

- `graph` — `rdm/graph/` (pyoxigraph, oxigraph, pyshacl, mcp).
- `validation` — Playwright, for the usability-persona runs.

RDM ships no planning tooling: Backlog.md (below) is used through its own CLI,
and nothing in RDM reads it (`docs/plan-vs-record.md`).

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
