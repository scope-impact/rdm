---
id: SDS-SYS-001
title: RDM System Architecture
context: system
# The bounded contexts, each with the part it belongs to (DI-58). Each has one
# design document, design/<context>.md; the table below describes them.
contexts:
  - {id: specification, part: Record}
  - {id: risk, part: Record}
  - {id: architecture, part: Record}
  - {id: test_evidence, part: Record}
  - {id: release, part: Gates}
  - {id: compliance, part: Gates}
  - {id: graph, part: Graph}
  - {id: publishing, part: Documents}
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
| **Graph** | the knowledge graph: the record as RDF, queried and validated; agents read it over MCP (`rdm graph mcp`) | no — rebuilt from the record, never edited |
| **Documents** | regulatory documents rendered from the record | no |

Only people and agents change the record, and only through a reviewed pull
request; the merge is the approval. Nothing RDM derives (graph, matrix,
documents, evidence bundle) is edited by hand or fed back into the record.

## System context (C1)

Who uses RDM and what it depends on. The architecture is one C4 model, the
workspace [`c4/workspace.dsl`](../c4/workspace.dsl); each view is drawn from
it (`rdm c4 draw`): the system context and the containers here, and each
bounded context's components (C3) in its own design document. RDM reads the
model (DI-66) and warns where code, tests and the model disagree (DI-68).

![System context: RDM](../c4/views/C1.svg)

## Containers (C2)

What runs or stores data. The bounded contexts are logical slices of these
containers: most of their components run in `rdm`, the pytest plugin in the
acceptance test run, and the reusable CI in GitHub Actions.

![Containers: RDM](../c4/views/C2.svg)

## Bounded contexts (one design document each), by part

A bounded context is drawn where the language changes, not where the workflow
does (`CONTEXT.md`): the stages of a design input's life (declared, approved,
verified, released) are not contexts. Restructured in Design Review 30 from
ten contexts named for what their code did or for a workflow stage.

| Part | Context | Design document | Its language | Code today → target package |
|------|---------|-----------------|--------------|-----------------------------|
| Record | `specification` (core) | `design/specification.md` | user need, design input, tagged test, design review, approved, design gate | `rdm/record/sdd.py`, tag scanning in `rdm/record/allure.py`, `rdm/gates/design_gate.py` (less the release gate), `new_input.py`, `hooks.py`, `hook_files/`, `init.py`, `adopt.py`, `validation.py`, `persona*.py` → `rdm/specification/` |
| Record | `risk` | `design/risk.md` | hazard, harm, severity, probability, control, residual | `rdm/record/risk.py` → `rdm/risk/` |
| Record | `architecture` | `design/architecture.md` | person, software system, container, component, relationship, view | `rdm/record/c4.py`, `rdm/c4.py` → `rdm/architecture/` |
| Record | `test_evidence` | `design/test_evidence.md` | test run, executor, step, attachment; Allure's and xunit's words stop here | results in `rdm/record/allure.py`, `pytest_plugin.py`, `translate.py`, `test_formatters/`, `gates/mutation.py` → `rdm/evidence/` |
| Gates | `release` | `design/release.md` | verified, release-grade evidence, release gate, evidence bundle | `run_release_gate` and trace in `design_gate.py`, `record/verify.py`, `record/bundle.py`, the reusable CI → `rdm/release/` |
| Gates | `compliance` | `design/compliance.md` | standard, checklist, checklist clause, gap, coverage | `rdm/gaps.py`, `rdm/checklists/` → `rdm/compliance/` |
| Graph | `graph` | `design/graph.md` | knowledge graph, projection, vocabulary, gate rule, derived relation | `rdm/graph/` (unchanged) |
| Documents | `publishing` | `design/publishing.md` | template, data, rendered document | `render.py`, `md_extensions/`, `collect.py`, `record/dmr.py`, `record/report.py` + layout, the PDF action → `rdm/publishing/` |

Not contexts: the **shared kernel** (`rdm/util.py`, `rdm/record/ids.py`,
`git.py`, `reconcile.py`, frontmatter parsing → `rdm/kernel/`), drawn in
`specification` until it moves; and **onboarding** (`init`, `adopt`), the
commands that create the record, which belong to `specification` as its
application layer. `rdm/main.py` is the composition root that wires the
command line to every context.

## Dependency rule

A context depends only on contexts below it; nothing imports upward, and
the read models at the top are imported by nothing.

```
   publishing · graph            read models: read every context, feed none back
         │
      release                    decides: verified status, risk and validation → release
         │
   test_evidence · risk · architecture · compliance
         │                       each conforms to the specification's ids
   specification                 the core: needs, inputs, tagged tests, review, design gate
         │
   shared kernel                 util · ids · git · frontmatter · reconcile
```

The code does not keep this rule yet. The imports between components
(`rdm/record/c4.py`, from the workspace) show two cycles, and both have
one cause:

- `specification` ↔ `test_evidence`: the release gate and the trace live
  in `design_gate.py`, and `allure.py` holds both the test tags (the
  specification's) and the results (the evidence's);
- `specification` ↔ `risk`: the release gate reads risk findings, and
  `risk.py` borrows the specification reader's frontmatter parser.

Moving the release gate and the trace to `release`, splitting `allure.py`
into tags and results, and moving the frontmatter parser to the kernel
removes both. The workspace draws today's code paths; each move updates a
component's `code` path, not its context.

## Flow

Specification → its evidence, risks and architecture → Release decides (the
design gate before implementation, the release gate before release; gap
analysis against checklists) → the knowledge graph and the documents are
derived from the same record. Agent skills (how to author design inputs, test-first,
risk analysis) are maintained outside RDM, in `scope-impact/agent-skills`.
RDM ships no planning tooling: tasks and issues live in their own tools,
outside the record (see `docs/plan-vs-record.md`).

## Not yet in scope

In rough order: turning a regulation into a checklist mapped to its
standard's clauses; pushing the graph to an external RDF store, with the
vocabulary and gate shapes versioned; and several projects in one graph
(clause identifiers are already project-independent). Each enters through a
user need and design inputs in this record before any code.
