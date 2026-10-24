# Source: innolitics/rdm

- **Repository:** https://github.com/innolitics/rdm
- **Pinned at:** `f21b508`, 2022-09-06, "Merge pull request #113 from jayvdb/add-github-backend-test"
- **What it is:** Regulatory Documentation Manager, by Innolitics: Markdown
  templates and Python scripts that produce medical-device software documents
  (IEC 62304) from a git repository. The ancestor of `scope-impact/rdm`.

## Evidence for

| Topic | Where | Used by |
|---|---|---|
| Documents written as code, reviewed in pull requests beside the code | `README.md`, "Benefits of Using RDM" | UN-001 |
| A project started from one command (`rdm init`), templates kept in the repository | `README.md`, "Typical Workflow" steps 1 to 3, and "The Init Files" | UN-009 |
| Git hooks installed by a command (`rdm hooks`) | `README.md`, "Git Hooks" | UN-002 |
| Traceability generated, never written | `README.md`, "Benefits of Using RDM" | UN-003 |
| Developer-first, readable documents; easy to migrate away from | `README.md`, "Our Design Goals for RDM" 1, 3, 5 | UN-010 |
| A top-level architecture document kept in the repository, subdividing the project into software items | `README.md`, "Typical Workflow" steps 5 and 9 | UN-005 |
| Templates plus data rendered to Markdown, then PDF or Word | `README.md`, "Templating and Data Files" | UN-010 |
| Gap checks against audit checklists | `README.md`, "Audit Checklists" | (regulated profile, not in the first version) |

## Fenced

This source's implementation is fenced: never cited, and removed from the
checkout a clean room carries. The paths are in `sources.edn`, `:innolitics-rdm`.
