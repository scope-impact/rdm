---
id: SDS-HAR-001
title: Pi harness — system architecture
context: system
# The bounded contexts of Löyly's pi harness, one per slice of its plan
# (loyly/.pi/plan), each with the code the builder generated for it.
contexts:
  - {id: specification, part: Specify, code: [harness-impl/01-specification/]}
  - {id: measurement, part: Measure, code: [harness-impl/02-measurement/]}
  - {id: grader-lifecycle, part: Measure, code: [harness-impl/03-grader-lifecycle/]}
  - {id: regeneration-cycle, part: Regenerate, code: [harness-impl/04-regeneration-cycle/]}
  - {id: cycle-preparation, part: Operate, code: [harness-impl/05-cycle-preparation/]}
  - {id: cycle-runner, part: Operate, code: [harness-impl/06-cycle-runner/]}
references: [RMF-HAR-001]
---

# Pi harness — system architecture

The pi harness is Löyly's regeneration harness: it stamps a spec, prepares a
cycle on a pinned source cut, fences a clean room, has a builder regenerate
the code from the spec alone, gates the candidate, and grades it against the
spec's checklist with approved graders. Its requirements are the 201 stamped
scenarios of Löyly's spec v2; its user needs, the screens of each context's
event model ([[62304:5.3.1]]).

| Context | What it owns | Code |
|---|---|---|
| specification | stamping: an item identity per accepted scenario, never reused | `harness-impl/01-specification/` |
| measurement | the checklist of a source cut, item verdicts, scores and the scoreboard | `harness-impl/02-measurement/` |
| grader-lifecycle | instrument goldens, written from the scenario and moved only for a recorded reason | `harness-impl/03-grader-lifecycle/` |
| regeneration-cycle | the self-check, the fence and its clean room, the gate and its attempts | `harness-impl/04-regeneration-cycle/` |
| cycle-preparation | the pinned source cut | `harness-impl/05-cycle-preparation/` |
| cycle-runner | runs a cycle: launches the builder, waits, hands verdicts in | `harness-impl/06-cycle-runner/` |

The contexts meet only through the events each hands to another (each
context's `event-model/handoffs.edn`) and the seams its `seam.md` describes
([[62304:5.3.2]]). The fence and the grader approval are where the
architecture controls the risks in RMF-HAR-001 ([[62304:5.2.3]]).

This record is kept from outside Löyly: the spec and the code are the `loyly`
submodule, and every acceptance suite runs unchanged; the evidence is their
Allure results, each tied to the Löyly commit it tested
([[62304:5.7.4.b]], [[62304:5.7.4.c]], [[62304:5.7.4.d]]).
