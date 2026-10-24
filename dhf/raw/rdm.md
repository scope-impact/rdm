# Source: scope-impact/rdm

- **Repository:** https://github.com/scope-impact/rdm
- **Pinned at:** `3763e08`, the default branch before rdm-spec's spec was
  started ("Merge pull request #56 from scope-impact/claude/ifu"). At cutover
  this commit is also tagged `pre-spec`.
- **What it is:** RDM as it stands: the design record of regulated software as
  Markdown and tests in git, gated, rendered and queryable as a graph, and
  governing its own development (dogfood).

## Evidence for

| Topic | Where | Used by |
|---|---|---|
| Tests as the executable behaviour spec ("live BDD") | `dhf/decisions/adr-0001-bounded-context-user-needs.md`, "Tests + Allure tags are the executable behaviour spec" | UN-001 |
| The design gate, run before a commit by hooks | `dhf/AGENT_WORKFLOW.md`; `docs/reference/cli.md`, `design-gate`, `hooks` | UN-002 |
| Verification status derived from executed Allure results; the trace slice | `docs/reference/cli.md`, `verify`, `trace`; `dhf/documents/verification_and_validation_plan.md`, UN-004 | UN-003 |
| A release blocked until every need is verified | `dhf/documents/verification_and_validation_plan.md`, UN-003; `docs/reference/cli.md`, `release-gate` | UN-004 |
| The architecture as C4 in the record, checked against code and tests | `dhf/documents/verification_and_validation_plan.md`, UN-017; `docs/reference/cli.md`, `rdm c4 draw` | UN-005 |
| Independent review: a reviewer other than the author approves | `dhf/AGENT_WORKFLOW.md`, "The intent" item 4 | UN-007 |
| Scaffolding a new project; adopting an existing repository without overwriting | `dhf/documents/verification_and_validation_plan.md`, UN-008, UN-011; `docs/reference/cli.md`, `init`, `adopt` | UN-009 |
| A published documentation site | `mkdocs.yml`; `docs/index.md` | UN-010 |
| One design document per bounded context; user needs across contexts | `dhf/decisions/adr-0001-bounded-context-user-needs.md` | the model (one container per context) |

## Fenced

This source's implementation is fenced: never cited, and removed from the
checkout a clean room carries. The paths are in `sources.edn`, `:rdm`.
