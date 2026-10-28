# Slice 12: Several specs

## Goal

A project keeps one spec, or several, one folder each with its own needs, model, events, glossary, ids and id prefix, and every task reads each on its own terms, or the one it is told.

## References

- UN-001 (`dhf/spec/needs.edn`)
- UN-003 (`dhf/spec/needs.edn`)
- UN-004 (`dhf/spec/needs.edn`)
- UN-007 (`dhf/spec/needs.edn`)
- UN-008 (`dhf/spec/needs.edn`)
- spec-29, from halla-health's wallet, whose SDDs are one folder each

## Scope

**In:**

- The parser: :specs read, and refused when it cannot be read apart
- The check: each spec on its own terms
- The stamper: each spec from its own ledger
- The checklist and verify: a cycle scoped to one spec
- Status, trace and release: every spec, or the one named

**Out:**

- A project in a folder: 11-project-in-a-folder
- Slices (one spec grouped by slice): 04-model-the-architecture

## Decisions

In the comments above the rules they shaped, each citing its incident (`README.md`, "A guard cites its incident"); a cycle's builder decisions are in `dhf/cycles/NN/decisions.yaml`.
