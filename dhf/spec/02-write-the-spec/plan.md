# Slice 02: Write the spec

## Goal

A team keeps its requirements as executable scenarios in a declared subset of Gherkin, each with one id from the ledger, one level and a need, and is stopped before a spec that breaks its rules is committed, told which scenario and why.

## References

- UN-001 (`dhf/spec/needs.edn`)
- UN-002 (`dhf/spec/needs.edn`)
- UN-003 (`dhf/spec/needs.edn`)
- UN-009 (`dhf/spec/needs.edn`)

## Scope

**In:**

- The parser: the spec read at a revision, the Gherkin subset, one spec or several (until 12-several-specs holds it)
- The stamper: ids handed out once, from the ledger
- The spec check: one id, one level, a need for every scenario, a source for every need, planned work, no stack words

**Out:**

- Sources and the wiki: 03-ground-the-spec
- The model's ownership of feature files: 04-model-the-architecture

## Decisions

In the comments above the rules they shaped, each citing its incident (`README.md`, "A guard cites its incident"); a cycle's builder decisions are in `dhf/cycles/NN/decisions.yaml`.
