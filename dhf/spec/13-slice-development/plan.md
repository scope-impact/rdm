# Slice 13: Slice development

status: ready

## Goal

A team works slice by slice: each slice planned, specified, built whole with
every component it reaches, verified, graded and closed, saying where it
stands and what was decided in it, so the spec is the sum of finished slices.

## References

- UN-001 (`dhf/spec/needs.edn`)
- UN-006 (`dhf/spec/needs.edn`)
- UN-008 (`dhf/spec/needs.edn`)
- `dhf/decisions/adr-0004-slices.md`, `dhf/decisions/adr-0005-slice-development.md`
- `dhf/wiki/pages/loyly-method.md`

## Scope

**In:**

- The slices layout: a slice folder, its plan, one feature file per component it reaches (model)
- A slice planned, with its status and its decisions (check)
- A touched slice a scope, whole; fenced and verified on the components it reaches (scopes, fence, verify)
- A cycle building one slice; its checklist telling the slice's items from its regressions; its score counting both apart (checklist, scorer)
- Status per slice, and a release of one slice (status, release)

**Out:**

- Several specs in one project: 12-several-specs
- A project in a folder: 11-project-in-a-folder

## Decisions

`decisions.md`, beside this plan.
