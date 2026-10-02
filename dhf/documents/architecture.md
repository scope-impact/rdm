---
id: SDS-SYS-001
title: RDM System Architecture
context: system
# The bounded contexts, each with the part it belongs to (DI-58). Each has one
# design document, design/<context>.md; the table below describes them.
contexts:
  - {id: record, part: Record}
  - {id: ingestion, part: Record}
  - {id: scaffolding, part: Record}
  - {id: risk, part: Record}
  - {id: gating, part: Gates}
  - {id: verification, part: Gates}
  - {id: gap_analysis, part: Gates}
  - {id: validation, part: Gates}
  - {id: graph, part: Graph}
  - {id: rendering, part: Documents}
# The record is controlled as DC-001 states.
references: [DC-001]
---

# System Architecture

RDM keeps the design record of regulated software as Markdown and tests in
git, checks it, renders regulatory documents from it, and builds it into a
read-only graph that people and agents query. This document holds **design
only** — user needs live in the V&V plan; a context serves the needs its
design inputs trace to (`traces_to`), so that is never declared twice.

## The four parts

| Part | Responsibility | Writes to the record? |
|------|----------------|-----------------------|
| **Record** | read and scaffold the record: needs, design inputs, checklists, tagged tests, results, git | scaffolding only (`init`, `adopt`, `new-input`); every other change is a reviewed commit |
| **Gates** | pass/block decisions on the record | no |
| **Graph** | the record as RDF, queried and validated; agents read it over MCP (`rdm graph mcp`) | no — rebuilt from the record, never edited |
| **Documents** | regulatory documents rendered from the record | no |

Only people and agents change the record, and only through a reviewed pull
request; the merge is the approval. Nothing RDM derives (graph, matrix,
documents, evidence bundle) is edited by hand or fed back into the record.

## System context (C1)

Who uses RDM and what it depends on. The architecture is kept as C4
diagrams: the system context and the containers here, and each bounded
context's components (C3) in its own design document. RDM reads them into
one model (DI-66) and warns where code, tests and diagrams disagree (DI-68).

```mermaid
C4Context
  title System context: RDM
  Person(author, "Regulatory author", "Writes the record; renders and checks the regulatory documents")
  Person(engineer, "Engineer", "Changes the product and its tagged acceptance tests")
  Person(reviewer, "Reviewer", "Approves each change by reviewing its pull request")
  System(rdm_system, "RDM", "Keeps the design record of regulated software, gates it, renders it and builds it into a read-only graph")
  System_Ext(product_repo, "Product repository", "git: the Markdown record, the tests and their Allure results")
  System_Ext(forge, "GitHub", "Pull requests, Actions and the image registry")
  System_Ext(agent_harness, "Agent harness", "An MCP client such as Claude Code, working under a person's direction")
  System_Ext(graph_explorer, "AWS Graph Explorer", "A graph browser")
  Rel(author, rdm_system, "writes the record and renders documents with")
  Rel(engineer, rdm_system, "runs the gates and tests with")
  Rel(reviewer, forge, "approves pull requests on")
  Rel(rdm_system, product_repo, "reads the record, tests, results and history from")
  Rel(forge, rdm_system, "runs the gates of", "GitHub Actions")
  Rel(agent_harness, rdm_system, "reads the record through", "MCP")
  Rel(graph_explorer, rdm_system, "browses the graph of", "SPARQL over HTTP")
```

## Containers (C2)

What runs or stores data. The bounded contexts are logical slices of these
containers: most of their components run in `rdm`, the pytest plugin in the
acceptance test run, and the reusable CI in GitHub Actions.

```mermaid
C4Container
  title Containers: RDM
  Person(author, "Regulatory author")
  Person(engineer, "Engineer")
  System_Boundary(rdm_system, "RDM") {
    Container(rdm_cli, "rdm", "Python", "The command line: gates, rendering, graph build, query and validate, and the agent server")
    Container(test_run, "Acceptance test run", "pytest, allure-pytest", "Runs the tagged tests; RDM's plugin labels each run from the record")
    Container(gates_ci, "Reusable gates", "GitHub Actions", "The workflow and actions other repositories call")
    ContainerDb(graph_store, "Graph store", "Oxigraph", "The record projected into RDF")
    Container(sparql_endpoint, "SPARQL endpoint", "Oxigraph server", "Serves the store read-only")
    Container(documents_image, "Documents image", "Docker: Ubuntu, Pandoc, Typst", "Renders the documents to PDF")
  }
  System_Ext(product_repo, "Product repository", "git")
  System_Ext(forge, "GitHub")
  System_Ext(agent_harness, "Agent harness", "MCP client")
  System_Ext(graph_explorer, "AWS Graph Explorer")
  Rel(author, rdm_cli, "renders documents and checks the record with")
  Rel(engineer, test_run, "runs")
  Rel(rdm_cli, product_repo, "reads the record, results and git history from")
  Rel(test_run, product_repo, "writes Allure results to")
  Rel(rdm_cli, graph_store, "builds")
  Rel(sparql_endpoint, graph_store, "serves, read-only")
  Rel(graph_explorer, sparql_endpoint, "queries", "SPARQL over HTTP")
  Rel(agent_harness, rdm_cli, "calls the agent server of", "MCP over stdio")
  Rel(forge, gates_ci, "runs on every push and pull request")
  Rel(gates_ci, test_run, "runs")
  Rel(gates_ci, rdm_cli, "installs and runs")
  Rel(documents_image, rdm_cli, "renders with")
```

## Bounded contexts (one design document each), by part

| Part | Context | Design document | Modules |
|------|---------|-----------------|---------|
| Record | `record` | `design/record.md` | `rdm/record/` — design/V&V frontmatter, Allure results, git |
| Record | `ingestion` | `design/ingestion.md` | `rdm/collect.py`, `rdm/translate.py` — code snippets, foreign test results |
| Record | `scaffolding` | `design/scaffolding.md` | `rdm/init.py`, `rdm/adopt.py`, `rdm story new-input` |
| Record | `risk` | `design/risk.md` | `rdm/record/risk.py` — the risk register, scored from the risk matrix; its release rules |
| Gates | `gating` | `design/gating.md` | `rdm/gates/design_gate.py`, `rdm/hook_files/pre-commit` — design gate (including duplicate ids), release gate |
| Gates | `verification` | `design/verification.md` | `rdm/record/verify.py`, `rdm/gates/mutation.py` — inputs vs results, traceability matrix, mutation probe |
| Gates | `gap_analysis` | `design/gap_analysis.md` | `rdm/gaps.py`, `rdm/checklists/` — documents vs checklists |
| Gates | `validation` | `design/validation.md` | `rdm/record/persona.py`, `rdm/record/validation.py` — formative usability evidence |
| Graph | `graph` | `design/graph.md` | `rdm/graph/` — RDF projection, SHACL gate shapes, SPARQL, Graph Explorer file, read-only MCP server |
| Documents | `rendering` | `design/rendering.md` | `rdm/render.py`, `rdm/md_extensions/` — templates + data → Markdown → PDF/DOCX |

## Flow

Record → Gates decide (design gate before implementation, release gate before
release, gap analysis against checklists) → Graph and Documents are derived
from the same record. Agent skills (how to author design inputs, test-first,
risk analysis) are maintained outside RDM, in `scope-impact/agent-skills`.
RDM ships no planning tooling: tasks and issues live in their own tools,
outside the record (see `docs/plan-vs-record.md`).

## Not yet in scope

In rough order: turning a regulation into a checklist mapped to its
standard's clauses; pushing the graph to an external RDF store, with the
vocabulary and gate shapes versioned; and several projects in one graph
(clause identifiers are already project-independent). Each enters through a
user need and design inputs in this record before any code.
