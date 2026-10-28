# Slice 06: Regenerate

## Goal

A team regenerates its implementation from a merged cut of the spec, in a clean room that provably holds no earlier implementation, and a build is done only when a check the builder does not control says so.

## References

- UN-006 (`dhf/spec/needs.edn`)
- UN-007 (`dhf/spec/needs.edn`)
- `dhf/wiki/pages/loyly-method.md`

## Scope

**In:**

- Cut: a pushed tag on the default branch, read and nothing else
- Scopes: what a spec change touched, in the order a builder builds it
- Fence: a clean room, the cut minus what is denied, for the project, a context, a component or a slice
- Verify: the cut trusted alone; the project's checks (tests, serves, runs, plugin) run the same way and recorded

**Out:**

- Grading and scoring: 07-measure
- The plugin check's request: 08-plugins

## Decisions

In the comments above the rules they shaped, each citing its incident (`README.md`, "A guard cites its incident"); a cycle's builder decisions are in `dhf/cycles/NN/decisions.yaml`.
