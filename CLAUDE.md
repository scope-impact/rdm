# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development

```bash
uv sync --all-extras          # Install all dependencies (dev, graph, docs, report)
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

## RDM's own record (dogfood)

RDM audits a spec rather than authoring one (ADR 0002): writing the spec and
regenerating code from it belong to the factory, Löyly (`loyly/`, submodule
on `spec-v2`). RDM reads a spec in Löyly's layout and checks it with its own
gates; RDM's own spec is in that layout too.

- **The spec** — `.pi/plan/NN-<context>` → `.pi/spec/<context>/`:
  `spec.feature` holds the requirements, stamped scenarios `@item-NN-SSS`;
  `allium/*.allium` the user needs as surfaces (`facing`, `@guarantee`). A
  scenario addresses a need through its `@allium-guarantee.<Need>` tag. RDM
  reads it with `rdm/specification/spec.py`; `rdm/kernel/edn.py` reads event
  models and event-storm boards.
- **The rest of the record** — `dhf/`: the design documents (one per context,
  design output only), the risk policy and register (`controls:` name item
  ids), the V&V plan, validation records, document control. Never hand-edit
  the traceability matrix — it is generated.
- **Tests** — an acceptance test claims the requirement it verifies with
  `@allure.tag("item-NN-SSS")` and checks only what that scenario states.
- **Vocabulary** — `CONTEXT.md` is the glossary; use its terms.
- **CI** — `.github/workflows/design-controls.yml` runs acceptance tests
  (Allure) → verify → release gate → graph validation → evidence bundle. A
  change that leaves a requirement unverified fails CI. A reviewer other than
  the author approves each pull request.

Run the gates locally exactly as CI does:

```bash
uv run pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

## Architecture

RDM keeps the design record of regulated software (IEC 62304 first) as Markdown and tests in git, in four parts:
**Record** (the spec it reads, checklists, risks, documents, test results; changed only by a reviewed PR), **Gates** (release
gate, gap analysis), **Graph** (the record as a read-only RDF graph for people and agents; never edited), and
**Documents** (YAML data + Jinja2 templates → Markdown → PDF/DOCX via Pandoc/Typst). `dhf/documents/architecture.md`
assigns each bounded context to a part. Agent skills live in scope-impact/agent-skills, not here.

### Key modules

One package per bounded context (`dhf/documents/architecture.md` gives each its
layer; `tests/dependency_rule_test.py` fails on an import that breaks the rule):

- `rdm/main.py` — the composition root: the `rdm` command; argparse subcommands dispatch to the contexts.
- `rdm/kernel/` — the shared kernel: YAML and files (`util.py`), EDN (`edn.py`), ids, git, frontmatter, the reconcile helpers.
- `rdm/specification/` — the core: the spec reader (`spec.py`, `sdd.py`), `init`/`adopt` and their templates, validation records.
- `rdm/evidence/` and `rdm/pytest_plugin.py` — test evidence: Allure results (`allure.py`); the plugin labels each run with the commit under test.
- `rdm/risk/`, `rdm/compliance/` — the leaves: the risk register; gap analysis and the built-in checklists.
- `rdm/release/` — the release gate (`gate.py`), the verification data (`verify.py`).
- `rdm/publishing/` and `rdm/md_extensions/` — templates and data to Markdown (`render.py`, two-pass, filters `invert_dependencies`, `join_to`, `md_indent`), snippets, the DMR index, the verification report, the evidence bundle; Markdown post-processing.
- `rdm/graph/` — The design record projected into RDF (`rdm graph build | query | validate | serve | explorer-file | mcp`, extra `graph`): named graphs per source, embedded Oxigraph store, SHACL gate shapes, SPARQL endpoint for AWS Graph Explorer, and a read-only MCP server for agents (`agent.py`, registered in `.mcp.json`). See `docs/use/graph.md`.

### Optional extras

- `graph` — `rdm/graph/` (pyoxigraph, oxigraph, pyshacl, mcp).

RDM ships no planning tooling, and nothing in RDM reads a planning tool
(`docs/about/plan-vs-record.md`).
