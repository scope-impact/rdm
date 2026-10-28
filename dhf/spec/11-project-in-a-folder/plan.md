# Slice 11: A project in a folder

## Goal

A project may be one folder of a larger repository, as halla-health's wallet is: every task run in that folder reads the folder, at the cut as committed, and nothing outside it.

## References

- UN-006 (`dhf/spec/needs.edn`)
- UN-007 (`dhf/spec/needs.edn`)
- spec-27, from halla-health's wallet (dhf/cycles/23/cycle.edn)

## Scope

**In:**

- The command: the project's root is the folder its configuration is in
- Fence: the folder as committed at the cut
- Verify: the room's implementation reaches the checkout as git would commit it
- The checklist: the folder's cycles
- Scopes: the change within the folder

**Out:**

- Several specs in one project: 12-several-specs

## Decisions

In the comments above the rules they shaped, each citing its incident (`README.md`, "A guard cites its incident"); a cycle's builder decisions are in `dhf/cycles/NN/decisions.yaml`.
