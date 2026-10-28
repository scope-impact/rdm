# Slice 05: Verify by results

## Goal

A team sees each scenario's status derived from executed test results, whatever stack ran them, traces a need to its evidence, and cannot release until every scenario is verified.

## References

- UN-002 (`dhf/spec/needs.edn`)
- UN-003 (`dhf/spec/needs.edn`)
- UN-004 (`dhf/spec/needs.edn`)

## Scope

**In:**

- The runner: one small step API, one Allure result per scenario, labelled with what it verifies
- Results: Allure results as the oracle, whoever wrote them
- Status, trace, and the release check (its regulated rule until 10-regulated-profile holds it)

**Out:**

- Verify's checks on a cycle's build: 06-regenerate

## Decisions

In the comments above the rules they shaped, each citing its incident (`README.md`, "A guard cites its incident"); a cycle's builder decisions are in `dhf/cycles/NN/decisions.yaml`.
