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
| A project started from one command (`rdm init`), templates kept in the repository | `README.md`, "Typical Workflow" steps 1 to 3; `rdm/init.py`; `rdm/init_files/` | UN-009 |
| Git hooks installed by a command (`rdm hooks`) | `README.md`, "Git Hooks"; `rdm/hooks.py` | UN-002 |
| Traceability generated, never written | `README.md`, "Benefits of Using RDM" | UN-003 |
| Developer-first, readable documents; easy to migrate away from | `README.md`, "Our Design Goals for RDM" 1, 3, 5 | UN-010 |
| Templates plus data rendered to Markdown, then PDF or Word | `README.md`, "Templating and Data Files"; `rdm/render.py` | UN-010 |
| Gap checks against audit checklists | `README.md`, "Audit Checklists"; `rdm/gaps.py` | (regulated profile, not in the first version) |
