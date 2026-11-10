# RDM's spec

RDM's own requirements and user needs, written the Löyly way: one directory per bounded
context, each a slice of `../plan/` (`NN-<context>` links here).

| Slice | Context | Needs (event-model screens) |
|---|---|---|
| 01 | `specification/` | scaffold-project, adopt-repository, validation-visible |
| 02 | `risk/` | risk-register |
| 03 | `test_evidence/` | (none: its requirements serve release's trace-verification) |
| 04 | `release/` | release-blocked, trace-verification, release-evidence |
| 05 | `compliance/` | gap-analysis |
| 06 | `graph/` | explore-graph, agent-reads-record |
| 07 | `publishing/` | compile-dhf, user-manual |

In each context: `spec.feature` holds the accepted scenarios, each a concrete
Given/When/Then example stamped `@item-NN-SSS` (a requirement) and tagged with the Allium
rule or guarantee it covers; `allium/` the rules, actors and surfaces; `event-model/` the
screens (one per user need, linked to its surface), commands and events.
`event-storm.edn` (and `event-storm.md`) is the board behind them. An acceptance test
claims the requirement it verifies with `@allure.tag("item-NN-SSS")`.
