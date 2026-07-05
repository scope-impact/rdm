<a href="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/">
  <img src="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/badge.svg?branch=main">
</a>

# Regulatory Documentation Manager

> **Fork Notice:** This is a maintained fork of [innolitics/rdm](https://github.com/innolitics/rdm). All credit for the original work goes to the [Innolitics](https://innolitics.com) team.

RDM is a documentation-as-code tool that provides Markdown templates and Python scripts to manage medical device software documentation. It's especially well-suited for software-only medical devices following IEC 62304.

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

Full documentation lives at **[scope-impact.github.io/rdm](https://scope-impact.github.io/rdm/)**:
installation and quickstarts, a task-oriented user guide (authoring/rendering,
gap analysis, the `rdm story` design-controls workflow), the complete CLI
reference, the record-first concepts, and the site's own live verification
evidence — the traceability matrix is generated from an acceptance-suite run
on every docs build.

RDM dogfoods itself: its own development is governed by its record-first design
controls (see `dhf/AGENT_WORKFLOW.md`), its own record is controlled in git per
the shipped 21 CFR Part 11 checklist (`dhf/documents/document_control.md`), and
`examples/github-document-control/` is a complete worked example of git as a
document control system with GitHub as the service provider.

## Development

```sh
git clone https://github.com/scope-impact/rdm.git
cd rdm
uv sync --all-extras
uv run pytest tests
```

## Changes from Upstream

### Unreleased

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
- **Replayable faithfulness reviews**: verdicts record their executed
  mutation probes; `rdm story faithfulness --replay` re-executes them and
  fails on survivors; `--stale` lists the review worklist
- **Verdict hash scope**: module-scope pinning by default (helper/fixture
  edits re-open the review), function scope selectable, legacy verdicts
  honored
- **Sound gap matching**: references count only inside `[[ … ]]` blocks,
  exact keys with descendant-covers-parent hierarchy — prose mentions and
  sibling keys no longer count as coverage
- **`rdm story dmr`** and **`rdm story evidence-bundle`**: DMR index data
  generated from frontmatter; the retained release evidence set (matrix,
  verification data, verdicts, manifest)
- `rdm hooks` defaults to the design-gate hook only (`--with-issue-hooks`
  opts into the legacy pair); `new-input` keeps `satisfies` lists in sync
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

## License

[MIT](LICENSE.txt) - Original work by [Innolitics](https://innolitics.com)
