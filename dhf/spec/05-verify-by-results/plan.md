# Slice 05: Verify by results

status: done

## Goal

A team sees each scenario's status derived from executed test results, whatever stack ran them, traces a need to its evidence, and cannot release until every scenario is verified.

## References

- UN-002 (`dhf/spec/needs.edn`)
- UN-003 (`dhf/spec/needs.edn`)
- UN-004 (`dhf/spec/needs.edn`)

## Scope

**In:**

- The runner: the step protocol (each step file a command, in any language, speaking EDN), one Allure result per scenario, labelled with what it verifies
- Results: Allure results as the oracle, whoever wrote them
- Status, trace, and the release check (its regulated rule until 10-regulated-profile holds it)

**Out:**

- Verify's checks on a cycle's build: 06-regenerate

## Decisions

`decisions.md`, beside this plan.
