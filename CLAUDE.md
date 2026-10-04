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

Note: the docs build runs the acceptance suites of RDM and of the Part 11
example (`docs/_hooks/traceability.py`) and draws each one's traceability from
its graph, so expect `mkdocs build` to take about a minute and to need the full
dev environment (`graph` extra) for real evidence; a map says why otherwise. The published design history file
(`docs/_hooks/dhf.py`) links each document's PDF. CI renders them once, in
the RDM image (the Docs workflow's `pdfs` job, which also renders a scaffolded
project's PDFs), and the docs build takes them as an artifact
(`RDM_DHF_PDFS=<dir>`); locally they are built when `pandoc` and `typst` are
on PATH, and the pages carry no PDF link otherwise.

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
  call; no separate verdict is recorded. A test checks only what its input's
  text states: before adding a step to an existing test, read the text, and
  amend it or declare a new input when it does not state the step. Before each
  release, a chain review reads every input with its whole chain, recorded as
  a design review (Design Review 59).

Run the gates locally exactly as CI does:

```bash
uv run rdm story design-gate --dhf dhf
uv run pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
uv run coverage run --source=rdm -m pytest tests --ignore=tests/acceptance -q   # unit tests, under coverage
uv run coverage xml -q -o dhf/data/unit-coverage.xml
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml \
  --unit-coverage dhf/data/unit-coverage.xml
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

## Architecture

RDM keeps the design record of regulated software (IEC 62304 first) as Markdown and tests in git, in four parts:
**Record** (needs, design inputs, checklists, tagged tests; changed only by a reviewed PR), **Gates** (design gate,
release gate, gap analysis), **Graph** (the record as a read-only RDF graph for people and agents; never edited), and
**Documents** (YAML data + Jinja2 templates → Markdown → PDF/DOCX via Pandoc/Typst). `dhf/documents/architecture.md`
assigns each bounded context to a part. Agent skills live in scope-impact/agent-skills, not here.

### Key modules

One package per bounded context (`dhf/documents/architecture.md` gives each its
layer; `tests/dependency_rule_test.py` fails on an import that breaks the rule):

- `rdm/main.py` — the composition root: the `rdm` command; argparse subcommands dispatch to the contexts.
- `rdm/kernel/` — the shared kernel: YAML and files (`util.py`), ids, git, frontmatter, the reconcile helpers.
- `rdm/specification/` — the core: the record reader (`sdd.py`), test tags (`tags.py`), the design gate (including duplicate ids), `new-input`, hooks, `init`/`adopt` and their templates, validation records, persona runs.
- `rdm/evidence/` and `rdm/pytest_plugin.py` — test evidence: Allure results (`allure.py`), `translate`, the mutation probe, the unit tests' code coverage (`unit_coverage.py`, never acceptance evidence); the plugin labels each run from the record.
- `rdm/risk/`, `rdm/architecture/`, `rdm/compliance/` — the leaves: the risk register; the C4 model and `rdm c4 draw`; gap analysis and the built-in checklists.
- `rdm/release/` — the release gate and trace (`gate.py`), the verification data (`verify.py`).
- `rdm/publishing/` and `rdm/md_extensions/` — templates and data to Markdown (`render.py`, two-pass, filters `invert_dependencies`, `join_to`, `md_indent`), snippets, the DMR index, the verification report, the evidence bundle; Markdown post-processing.
- `rdm/graph/` — The design record projected into RDF (`rdm graph build | query | validate | serve | explorer-file | mcp`, extra `graph`): named graphs per source, embedded Oxigraph store, SHACL gate shapes, SPARQL endpoint for AWS Graph Explorer, and a read-only MCP server for agents (`agent.py`, registered in `.mcp.json`). See `docs/use/graph.md`.

### Optional extras

- `graph` — `rdm/graph/` (pyoxigraph, oxigraph, pyshacl, mcp).
- `validation` — Playwright, for the usability-persona runs.

RDM ships no planning tooling: Backlog.md (below) is used through its own CLI,
and nothing in RDM reads it (`docs/about/plan-vs-record.md`).

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
