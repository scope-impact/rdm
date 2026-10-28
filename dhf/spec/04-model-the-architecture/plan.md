# Slice 04: Model the architecture

## Goal

A team keeps its architecture as one C4 model whose views are generated and checked, and whose components own the scenarios.

## References

- UN-005 (`dhf/spec/needs.edn`)

## Scope

**In:**

- The model: read whole or not at all; a component's feature file found from the model; views stale on content, never formatting
- The lint: the C4 checklist; bounded contexts as modules of one container, relating through events
- The views: one source, each level holding what C4 says it holds

**Out:**

- The event bus that carries the events: 08-plugins

## Decisions

In the comments above the rules they shaped, each citing its incident (`README.md`, "A guard cites its incident"); a cycle's builder decisions are in `dhf/cycles/NN/decisions.yaml`.
