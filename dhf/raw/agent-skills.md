# Source: scope-impact/agent-skills

- **Repository:** https://github.com/scope-impact/agent-skills
- **Pinned at:** `da65901`, 2026-10-12, "Merge pull request #6 from
  scope-impact/claude/remove-test-faithfulness"
- **What it is:** Scope Impact's agent skills. One of them, `c4-diagrams`, is
  Scope Impact's own guide to C4 models and diagrams: the model and its
  levels, when to draw which view, and a quality checklist. It holds no
  implementation of anything rdm-spec regenerates.

## Evidence for

| Topic | Where | Used by |
|---|---|---|
| The architecture as a C4 model: system context, containers, components | `skills/engineering/c4-diagrams/reference/c4-guide.md`, "Core Model", "Diagram Levels" | UN-005 |
| One model with views generated from it; the textual source authoritative | `skills/engineering/c4-diagrams/reference/c4-guide.md`, "Scale And Freshness", "Rendered Artifacts" | UN-005 |
| The quality checklist the lint enforces | `skills/engineering/c4-diagrams/reference/c4-guide.md`, "Quality Checklist", "Common Mistakes" | the C4 lint |
| A container runs or stores data; a shared library is not one | `skills/engineering/c4-diagrams/reference/c4-guide.md`, "Core Model", "Microservices And Shared Libraries" | one container per context; the kernel |

## Fenced

Nothing: this source holds no implementation of what rdm-spec regenerates.
