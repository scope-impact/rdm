---
id: SDS-SYS-001
title: RDM System Architecture
context: system
---

# System Architecture

RDM is a documentation-as-code CLI that compiles a Design History File from the
system of record (SDD + executed test results + git) and gates design controls
and verification. This document holds **design only** — user needs live in the
V&V plan; each context SDD declares the user needs it contributes to via
`satisfies`.

## Bounded contexts (one SDD each)

| Context | SDD | Responsibility (modules) |
|---------|-----|--------------------------|
| `record` | `sdd/record.md` | ingest the system of record: parse SDD/V&V frontmatter, Allure results, git history (`rdm/record/`) |
| `gating` | `sdd/gating.md` | design gate, release gate, pre-commit hook (`rdm/story_audit/design_gate.py`, `rdm/hook_files/pre-commit`) |
| `verification` | `sdd/verification.md` | reconcile user needs vs Allure; render the traceability matrix (`rdm/record/allure.py`, `verify.py`) |
| `validation` | `sdd/validation.md` | formative usability validation via the persona skill (`rdm/record/persona.py`, `.claude/skills/usability-persona/`) |
| `rendering` | `sdd/rendering.md` | Jinja templates + data → Markdown → PDF/DOCX (`rdm/render.py`, `rdm/md_extensions/`) |

## Cross-context flow

The `record` context reads the registry and executed results → `verification`
reconciles them and `gating` enforces approval (design gate) and verified
coverage (release gate) → `rendering` compiles the DHF, embedding the generated
traceability matrix → `validation` exercises the UI formatively against user
needs. Planning tooling (`rdm/project_management`, optional `rdm[plan]`) sits
outside the record (see `docs/plan-vs-record.md`).
