---
hide:
  - navigation
  - toc
---

# Worked example: Löyly's pi harness

[`examples/loyly-pi-harness`](https://github.com/scope-impact/rdm/tree/main/examples/loyly-pi-harness)
keeps the design history file of a real product with RDM, from outside it:
the regeneration harness of [Löyly](https://github.com/scope-impact/loyly),
an agentic software factory. Its spec (spec v2) holds 201 requirements as
stamped Gherkin scenarios in six contexts, with user needs in an event
model, Allium rules and an event storm; six clojure.test suites verify it.
Löyly knows nothing of RDM or Allure:

- RDM reads the spec from its `loyly` submodule (`spec:` in the example's
  `dhf/config.yml`), its needs through the Löyly adapter.
- `run-acceptance.sh` runs the harness's suites unchanged, with a small
  bridge (`bridge/user.clj`) on the JVM's boot class path that writes each
  test as an Allure result, tagged with the requirement it claims and
  labelled with the Löyly commit it tested.
- Its design history file holds the architecture (the six contexts, each a
  slice with its code), a risk policy and a register of four harness risks
  whose controls are Löyly requirements, the V&V plan and the traceability
  matrix, checked against the IEC 62304 clauses they claim
  (`checklists/pi_harness.txt`).
- RDM's gates then hold the harness to its own spec: all 201 requirements
  verified, every need addressed, every risk evaluated, every claimed clause
  referenced; the evidence bundle carries the verification report. CI runs it
  all on every change to RDM.

Its README says how to run it. Its traceability, from its knowledge graph,
the same way as [RDM's own](../traceability-map.md) (from the spec alone
when the build has no results of its suites):

<div class="rdm-map" data-src="../../assets/example-traceability-map.json">
--8<-- "docs/_hooks/traceability_map.html"
</div>
