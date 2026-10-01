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
user need → design input → tagged test → result → source file
risk → control (a design input) → tagged test → result
design document → the commit that landed it
```

Every link is a file you write or a fact a tool records. Nothing in the chain
is typed into a database by hand.

## Why

RDM started as a tool for people writing regulatory documents. It is being
extended so agents can work on regulated software with the same
traceability: a knowledge core that holds the whole design record —
regulations and the checklists drawn from them, design documents in bounded
contexts, user needs, design inputs, tests, results and risks — as one
linked graph.

- **One source of truth, changed only by authorized people.** The record is
  Markdown and tests in git. People and agents propose changes as pull
  requests, and a reviewer who is not the author approves them. The graph
  and every document are rebuilt from the record and never edited.
- **Any harness.** Agents read the record through an MCP server, which
  Claude Code, Warp and many open-source harnesses speak. A harness that can
  only run commands can use `rdm graph query` instead.
- **Any graph database.** The graph is RDF in standard vocabularies (OSLC
  RM, Dublin Core, PROV-O, SKOS), so any RDF store can load it. Oxigraph is
  the one RDM embeds.
- **Skills are the method; RDM is the check.** How to write requirements,
  analyse risk and develop test-first lives in
  [scope-impact/agent-skills](https://github.com/scope-impact/agent-skills).
  A skill decides what to write; RDM checks that what was written is
  complete, traced and verified.

## The four parts

| Part | What it is | Commands |
| --- | --- | --- |
| **Record** | User needs, design inputs (one design document per bounded context), the risk register, checklists, tests tagged `@allure.story("DI-n")`. Markdown + git. Changed only by a reviewed pull request — the approval is the merge. | `rdm init`, `rdm adopt`, `rdm story new-input`, `rdm story trace` |
| **Gates** | Machine checks: no implementation before the design is approved, no release until every input has a passing test, every need is addressed and every risk is scored, controlled and acceptable, no clause a checklist requires left unreferenced. | `rdm story design-gate`, `rdm story release-gate`, `rdm gap` |
| **Graph** | The record built into an RDF graph (Oxigraph): needs, inputs, contexts, documents, tests, results, commits, risks, checklist clauses. **Read-only** — rebuilt from the record, never edited. Agents read it through an MCP server; people browse it. | `rdm graph build \| query \| validate \| serve \| explorer-file \| mcp` |
| **Documents** | Regulatory documents (PDF/DOCX) rendered from the record: Markdown templates + YAML data → Pandoc/Typst. | `rdm render`, `make pdfs` |

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

## Direction

Not built yet, in rough order:

- Turning a regulation into a checklist mapped to its standard's clauses.
- `rdm graph push` to an external graph database, with the vocabulary and
  gate shapes versioned so another store reads them the same way.
- Several projects in one graph. Clause identifiers are already shared
  across repositories, so one query can ask which projects claim a clause.

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

- Allure is for acceptance tests only: each acceptance run attaches the text of
  the design input it verifies; unit tests carry no Allure, held by a guard test.
- Allure results as RDF (Design Review 14, `rdm/graph/allure.py`): each run in
  full — times, failure details, parameters, labels, links (DI-54); container
  fixtures (DI-55); output labels link runs to source files, and `trace` lists a
  design input's code (DI-56). The acceptance suite records a step per clause.
- Test evidence (Design Review 13): the release bundle keeps the executed Allure
  results with their attachments and containers (DI-30); the graph and `trace`
  carry each run's steps and attachments (DI-53); RDM's gate and risk tests
  record a step per clause and attach what they checked.
- Findings from the graph's own analysis (Design Review 12): `rdm graph serve`
  is read-only; the agent server refuses `SERVICE` and non-id `trace` input
  (DI-36, DI-42; RISK-TOOL-006/007). The graph records who landed each design
  document's change (DI-51) and links needs, risks and design documents to
  their documents (DI-52). Four oversized design inputs are split: DI-47..50.
- **One data model** (Design Review 11): DuckDB, the planning tooling
  (`rdm story audit | sync | backlog-validate | check-ids | validate`, `rdm pm`,
  `rdm pull`) and the `story-audit`, `analytics`, `github` and `plan` extras are
  removed; the gates move to `rdm/gates/` and need no extra. A duplicated
  user-need or design-input id now fails the design gate (DI-46).
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
