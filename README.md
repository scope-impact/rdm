<a href="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/">
  <img src="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/badge.svg?branch=main">
</a>

# RDM

RDM keeps the design record of regulated software — medical-device software
under IEC 62304 first — as Markdown and tests in git. It checks the record,
renders regulatory documents from it, and builds it into one read-only graph
that people and agents query.

The chain it holds:

```
regulation → checklist → clause ← document
user need → design input → tagged test → run → source file
risk → risk control (a design input) → tagged test → run
design document → the commit that landed it
```

Every link is a file you write or a fact a tool records; nothing is typed into
a database. The record changes only through a pull request that someone other
than its author approves. The graph and every document are rebuilt from it and
never edited.

## The four parts

| Part | What it is | Commands |
| --- | --- | --- |
| **Record** | User needs; design inputs, one design document per bounded context; the risk register; checklists; tests tagged `@allure.story("DI-n")`. Markdown and git. | `rdm init`, `rdm adopt`, `rdm story new-input`, `rdm story trace` |
| **Gates** | No implementation before the design is approved. No release until every design input has a passing test, every need is addressed, and every risk is evaluated with its risk controls verified. No required checklist clause left unreferenced. | `rdm story design-gate`, `rdm story release-gate`, `rdm gap` |
| **Graph** | The record as RDF: needs, inputs, contexts, documents, tests, runs, commits, risks, clauses. Read-only; agents read it over MCP, people browse it. | `rdm graph build \| query \| validate \| serve \| explorer-file \| mcp` |
| **Documents** | Regulatory documents rendered from the record: templates and YAML data → Markdown → PDF/DOCX. | `rdm render`, `make pdfs` |

Two choices shape it:

- **Any harness, any store.** Agents read the record through an MCP server
  (`rdm graph mcp`); a harness that can only run commands uses
  `rdm graph query`. The graph is RDF in standard vocabularies (OSLC RM, Dublin
  Core, PROV-O, SKOS), so any RDF store can load it.
- **Skills are the method; RDM is the check.** How to write requirements,
  analyse risk and develop test-first lives in
  [scope-impact/agent-skills](https://github.com/scope-impact/agent-skills).
  A skill decides what to write; RDM checks that it is complete, traced and
  verified.

## What it does not do

- It does not make a device compliant. It keeps the evidence straight; a
  regulator judges the evidence.
- A green release gate means every design input has a passing tagged test, not
  that the test proves it. The pull-request reviewer judges that
  (`rdm story mutation-probe` helps).
- Checklists are written by hand; nothing turns a regulation into one.
- The risk gate checks a register's form, not its truth: whether a risk control
  is effective is the reviewer's call. It ships no risk matrix; acceptability
  criteria are the project's to declare.

## Install

```sh
uv tool install git+https://github.com/scope-impact/rdm
```

Python 3.10+ and [uv](https://github.com/astral-sh/uv). PyPI's `rdm` is another
project; install from this repository, and upgrade with `uv tool upgrade rdm`.
Rendering documents needs Pandoc 2.14+, Typst and Make: use the Docker image
(Ubuntu 26.04 LTS, Pandoc 3.12, Typst 0.15, the fonts, and RDM with the graph
extra) or install them natively (`brew install pandoc typst`).

## Quick start

```sh
rdm init                     # a new project: a DHF skeleton and document templates in dhf/
rdm adopt .                  # or an existing repository: never overwrites a file

rdm story new-input --list   # contexts, taken ids, user needs
rdm story new-input --context <context> --traces-to UN-001 --text "The device shall …"

# The gates, as CI runs them (the tests need pytest and allure-pytest)
rdm story design-gate --dhf dhf
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm story release-gate --dhf dhf --allure-results dhf/allure-results

cd dhf && make pdfs          # or, with Docker: docker compose run rdm make pdfs
```

## In CI

```yaml
jobs:
  design-controls:
    uses: scope-impact/rdm/.github/workflows/gates.yml@v2.0.0-alpha
    with:
      rdm-ref: v2.0.0-alpha        # the same ref as after the @
```

This runs your acceptance tests, then the design gate, verify, the release
gate, graph validation and the evidence bundle, with RDM installed from the
pinned revision; `rdm adopt` lays this workflow down. For PDFs, add a step
`uses: scope-impact/rdm@v2.0.0-alpha`. Inputs and options are in
[the gates in your CI](docs/reusable-ci.md).

## Documentation

[scope-impact.github.io/rdm](https://scope-impact.github.io/rdm/): how RDM
works and its data model, getting started, one section per part, how RDM
controls itself (with a traceability matrix generated from a live test run),
and the CLI and API reference.

| | `docs/` | `dhf/` |
|---|---|---|
| **What it is** | RDM's user documentation, the site above | RDM's own design history file: the record RDM develops itself under |
| **Controlled?** | No: ordinary docs | Yes: the design gate and the release gate hold it |
| **Read by RDM's tools?** | No | Yes: the gates, `trace`, the graph, the evidence |

`docs/` explains; `dhf/` decides. The full comparison is in
[how RDM controls itself](docs/dogfood.md).

## Development

```sh
git clone https://github.com/scope-impact/rdm.git && cd rdm
uv sync --all-extras
uv run pytest tests
```

RDM is developed under its own design controls: every change follows
[`dhf/AGENT_WORKFLOW.md`](dhf/AGENT_WORKFLOW.md) — the record first, then the
tagged test, then the code — and [CLAUDE.md](CLAUDE.md) lists the gates to run
as CI does. The vocabulary is [CONTEXT.md](CONTEXT.md).

## Direction

Not built yet, in rough order:

- Turning a regulation into a checklist mapped to its standard's clauses.
- `rdm graph push` to an external graph database, with the vocabulary and gate
  shapes versioned so another store reads them the same way.
- Several projects in one graph; clause identifiers are already shared across
  repositories.

## Changes

See [CHANGELOG.md](CHANGELOG.md).

## Origin and license

RDM started as a fork of [innolitics/rdm](https://github.com/innolitics/rdm), the
document-rendering part above; credit for that work goes to the
[Innolitics](https://innolitics.com) team. [MIT](LICENSE.txt).
