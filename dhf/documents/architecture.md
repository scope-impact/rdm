---
id: SDS-SYS-001
title: RDM System Architecture
context: system
---

# System Architecture

RDM keeps the design record of regulated software as Markdown and tests in
git, checks it, renders regulatory documents from it, and builds it into a
read-only graph that people and agents query. This document holds **design
only** — user needs live in the V&V plan; each context's design document
declares the needs it contributes to via `satisfies`.

## The four parts

| Part | Responsibility | Writes to the record? |
|------|----------------|-----------------------|
| **Record** | read and scaffold the record: needs, design inputs, checklists, tagged tests, results, git | scaffolding only (`init`, `adopt`, `new-input`); every other change is a reviewed commit |
| **Gates** | pass/block decisions on the record | no |
| **Graph** | the record as RDF, queried and validated; the read interface for agents | no — rebuilt from the record, never edited |
| **Documents** | regulatory documents rendered from the record | no |

Only people and agents change the record, and only through a reviewed pull
request; the merge is the approval. Nothing RDM derives (graph, matrix,
documents, evidence bundle) is edited by hand or fed back into the record.

## Bounded contexts (one design document each), by part

| Part | Context | Design document | Modules |
|------|---------|-----------------|---------|
| Record | `record` | `design/record.md` | `rdm/record/` — design/V&V frontmatter, Allure results, git |
| Record | `ingestion` | `design/ingestion.md` | `rdm/collect.py`, `rdm/translate.py` — code snippets, foreign test results |
| Record | `scaffolding` | `design/scaffolding.md` | `rdm/init.py`, `rdm/adopt.py`, `rdm story new-input` |
| Record | `story_audit` | `design/story_audit.md` | `rdm/story_audit/` — ID integrity, traceability audit |
| Gates | `gating` | `design/gating.md` | `rdm/story_audit/design_gate.py`, `rdm/hook_files/pre-commit` — design gate, release gate |
| Gates | `verification` | `design/verification.md` | `rdm/record/verify.py`, `rdm/story_audit/mutation.py` — inputs vs results, traceability matrix, mutation probe |
| Gates | `gap_analysis` | `design/gap_analysis.md` | `rdm/gaps.py`, `rdm/checklists/` — documents vs checklists |
| Gates | `validation` | `design/validation.md` | `rdm/record/persona.py`, `rdm/record/validation.py` — formative usability evidence |
| Graph | `graph` | `design/graph.md` | `rdm/graph/` — RDF projection, SHACL gate shapes, SPARQL, Graph Explorer file |
| Documents | `rendering` | `design/rendering.md` | `rdm/render.py`, `rdm/md_extensions/` — templates + data → Markdown → PDF/DOCX |

## Flow

Record → Gates decide (design gate before implementation, release gate before
release, gap analysis against checklists) → Graph and Documents are derived
from the same record. Agent skills (how to author design inputs, test-first,
risk analysis) are maintained outside RDM, in `scope-impact/agent-skills`.
Planning tooling (`rdm/project_management`) sits outside the record (see
`docs/plan-vs-record.md`).

## Not yet in scope

Risk management (ISO 14971), turning a regulation into a checklist, and a
dedicated read-only agent interface over the graph. Each enters through a
user need and design inputs in this record before any code.
