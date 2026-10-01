<a href="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/">
  <img src="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/badge.svg?branch=main">
</a>

# RDM

RDM keeps the design record of regulated software — medical-device software
under IEC 62304 first — as Markdown and tests in git, checks it, and makes it
one queryable graph that people and agents read from.

The chain it holds:

```
regulation → checklist → clause ← document
user need → design input → tagged test → result → commit
risk → control (a design input) → tagged test → result
```

Every link is a file you write or a fact a tool records. Nothing in the chain
is typed into a database by hand.

## The four parts

| Part | What it is | Commands |
| --- | --- | --- |
| **Record** | User needs, design inputs (one design document per bounded context), the risk register, checklists, tests tagged `@allure.story("DI-n")`. Markdown + git. Changed only by a reviewed pull request — the approval is the merge. | `rdm init`, `rdm adopt`, `rdm story new-input`, `rdm story audit` |
| **Gates** | Machine checks: no implementation before the design is approved, no release until every input has a passing test, every need is addressed and every risk is scored, controlled and acceptable, no clause a checklist requires left unreferenced. | `rdm story design-gate`, `rdm story release-gate`, `rdm gap` |
| **Graph** | The record built into an RDF graph (Oxigraph): needs, inputs, contexts, documents, tests, results, commits, checklist clauses. **Read-only** — rebuilt from the record, never edited. Agents read it through an MCP server; people browse it. | `rdm graph build \| query \| validate \| serve \| explorer-file \| mcp` |
| **Documents** | Regulatory documents (PDF/DOCX) rendered from the record: Markdown templates + YAML data → Pandoc/Typst. | `rdm render`, `make pdfs` |

How agents should work with it — writing design inputs, test-first, risk
analysis — is kept as skills in
[scope-impact/agent-skills](https://github.com/scope-impact/agent-skills), not here.

## What it does not do

- It does not make a device compliant. It keeps the evidence straight; a
  regulator judges the evidence, not the tool.
- A green release gate means every design input has a passing tagged test. It
  does not mean the test proves the input — the pull-request reviewer judges
  that (`rdm story mutation-probe` helps them check).
- Checklists are written by hand. Nothing turns a regulation into a checklist.
- The risk gate checks a register's form, not its truth: whether a control is
  effective, and whether a residual is as low as practicable, are the reviewer's.
  It ships no risk matrix: acceptability criteria are the project's to declare.
- A built store (`rdm graph build --store`, `serve`) is as current as its last
  build; the agent server (`rdm graph mcp`) reads the record afresh on every call.

## Quick Start

### Docker (Recommended)

```sh
# Install rdm CLI
uv tool install git+https://github.com/scope-impact/rdm

# Scaffold project and build documents
rdm init
cd dhf
docker compose run rdm make pdfs
```

### Native Installation

```sh
# Install rdm CLI
uv tool install git+https://github.com/scope-impact/rdm

# Install dependencies (macOS)
brew install pandoc typst

# Scaffold project and build documents
rdm init
cd dhf
make pdfs
```

### Existing repository (brownfield)

```sh
# Lay down record-first design controls WITHOUT touching existing files:
# DHF skeleton, agent runbook, design-gate pre-commit hook, session
# bootstrap, and CI gates. Never overwrites; re-running is a no-op.
rdm adopt .
```

### Update

```sh
uv tool upgrade rdm
```

## GitHub Action

```yaml
# .github/workflows/pdfs.yml
name: Generate PDFs

on:
  push:
    paths: ['dhf/**']
  workflow_dispatch:

jobs:
  pdfs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: scope-impact/rdm@v1
```

| Input | Description | Default |
| --- | --- | --- |
| `dhf_path` | Path to the DHF directory | `dhf` |
| `version` | RDM Docker image version | `latest` |
| `artifact_name` | Name for the uploaded artifact | `regulatory-documents` |

## Dependencies

**Docker (recommended):** Just Docker. The image includes Pandoc 3.6, Typst 0.12, and required fonts.

**Native:**
- Python 3.10+
- [uv](https://github.com/astral-sh/uv)
- [Pandoc](https://pandoc.org/) 2.14+
- [Typst](https://typst.app/)
- Make

## Documentation

[scope-impact.github.io/rdm](https://scope-impact.github.io/rdm/) — getting
started, one section per part, the CLI reference, and the traceability matrix
generated from a live acceptance run at every docs build.

RDM is developed under its own design controls: its record is `dhf/`, and every
change goes through `dhf/AGENT_WORKFLOW.md`.

## Development

```sh
git clone https://github.com/scope-impact/rdm.git
cd rdm
uv sync --all-extras
uv run pytest tests
```

## Changes

### Unreleased

- Risk evaluation per the requirements skill (Design Review 10): no default
  matrix — a register needs a declared `risk_policy` with per-level
  acceptability; a residual is not evaluated until its controls are verified;
  `category` safety/security with `stride` and `linked`; optional residual
  severity; `status: proposed` warns.
- `rdm story mutation-probe` runs the test once unmutated and refuses a test that
  does not pass: an already-failing test was reported KILLED under any mutation (DI-34).
- **Risk register** (UN-016, DI-43/44/45): risks as frontmatter in `kind: risk`
  documents, evaluated against a declared `risk_policy`, each control a
  design input; the release gate and the graph shapes block a broken, unscored,
  uncontrolled or unaccepted risk; `trace` takes risk ids. RDM's own tool-risk
  register is `dhf/documents/risk/` (Design Review 9). `rdm story new-input` no
  longer duplicates an empty `design_inputs: []` and wraps its stub docstring.
- **Read-only agent interface** (`rdm graph mcp`, UN-015, DI-41/42): an MCP
  stdio server with `schema`, `query`, `trace` and `validate`, each answering
  from a fresh projection; no write tool, SPARQL Update refused, rows capped.
  User needs now carry their text in the graph (DI-35; Design Review 8).
- **Tag discovery**: Python test tags are read from decorators and `pytestmark` only, never from
  strings or comments (DI-40): fixture files written by a test no longer count
  as coverage. Every acceptance command passes `--clean-alluredir`, so repeated
  runs and docs builds no longer double the results (Design Review 7).
- **Four parts**: RDM restated as Record, Gates, Graph and Documents (README,
  docs, intended use, architecture — Design Review 6); agent skills maintained
  in scope-impact/agent-skills
- **The record as a graph** (`rdm graph build | query | serve`, extra
  `graph`): the DHF and its regulatory checklists projected into RDF named
  graphs (OSLC RM, Dublin Core, PROV-O and SKOS vocabulary; checklists as
  data), queried with SPARQL over an embedded Oxigraph store, checked against
  the gate rules as SHACL shapes (`rdm graph validate`), and browsable in AWS
  Graph Explorer — derived from the Markdown, never edited
- **Agent-era design controls, end to end**: canonical change procedure
  (`dhf/AGENT_WORKFLOW.md`), always-on local design gate (committed
  `.githooks/` + session bootstrap), and CI gates
- **`rdm adopt`**: bring an existing repository under record-first design
  controls from one command (never overwrites)
- **`rdm story new-input`**: scaffold a traced design input (frontmatter
  entry, failing tagged stub test, checklist)
- **Record-first-aware `rdm story audit`**: design-input tag coverage in the
  report and score
- **`part11_document_control` built-in checklist** + RDM's own Part 11-mapped
  document-control statement, enforced by an acceptance test
- **Worked example** `examples/github-document-control/`: git as document
  control with GitHub as provider — rulesets/settings as code, PR approval as
  the Part 11 e-signature, DMR/DHR analogs, drift-audit script, its own
  gated DHF
- **Docs site**: user manual (quickstarts, guides, CLI reference), Mermaid
  diagrams, and build-time-generated verification evidence
- **Sound gap matching**: references count only inside `[[ … ]]` blocks,
  exact keys with descendant-covers-parent hierarchy — prose mentions and
  sibling keys no longer count as coverage
- **`rdm story dmr`** and **`rdm story evidence-bundle`**: DMR index data
  generated from frontmatter; the retained release evidence set (matrix,
  verification data, manifest)
- `rdm hooks` defaults to the design-gate hook only (`--with-issue-hooks`
  opts into the legacy pair); `new-input` keeps `satisfies` lists in sync
- **Polyglot traceability**: JS/TS and Java test tags discovered for
  linkage and audit; legacy YAML workflow deprecated
- **Faithfulness gate retired**: `rdm story faithfulness` and `verdict`
  removed (DI-19/20/21/27/28, UN-009); `mutation-probe` kept as a standalone
  reviewer tool (DI-34) — its hash-pinned
  verdicts re-opened reviews on edits that changed no requirement;
  independent verification is now the human-reviewed pull request
- Fixes: `rdm gap --coverage` with built-in checklist names; tag-scanner
  false positive; root-container test skip

### v1.1.0

- **Story Audit module** (`rdm[story-audit]`): Backlog.md parser, schema validation, traceability audit, and duplicate ID detection
- **Bidirectional GitHub Sync** (`rdm[github]`): Push Backlog.md tasks to GitHub Issues/Milestones/Projects v2, pull PRs into DuckDB for analytics
- **VitalView example**: Software-only medical device (SaMD) worked example for the record-first model (user needs, bounded-context SDDs, AI-persona usability validation)
- Alias-based status normalization with actionable fix hints in validator
- Codebase simplification: removed dead code, deduplicated logic, fixed inefficiencies
- Added CLAUDE.md for Claude Code development guidance
- Dependency bumps: PyGithub 2.8.1, Ruff 0.14.13

### v1.0.0

- Installation via `uv tool install` directly from GitHub
- Migrated from LaTeX to Typst for PDF generation
- New lightweight Docker image (Alpine + Pandoc 3.6 + Typst 0.12)
- Added GitHub Action for CI/CD (`scope-impact/rdm@v1`)
- Fixed broken cross-references in software_plan.md template

## Origin and license

RDM started as a fork of [innolitics/rdm](https://github.com/innolitics/rdm), the
document-rendering part above; credit for that work goes to the
[Innolitics](https://innolitics.com) team. [MIT](LICENSE.txt).
