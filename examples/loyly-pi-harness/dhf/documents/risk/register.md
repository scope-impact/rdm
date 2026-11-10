---
id: RMF-HAR-001
title: Risk register — pi harness
kind: risk
status: proposed
risks:
  - id: RISK-HAR-001
    category: safety
    status: proposed
    hazard: "A regenerated candidate is scored with what it should not have seen."
    situation: "The clean room keeps a prior candidate, a scoring tool or a fenced path, and the builder copies from it."
    harm: "The scoreboard reports the spec regenerated when the code was copied, and the factory is trusted beyond what it can do."
    severity: Serious
    probability: Possible
    controls: [item-04-011, item-04-012, item-04-013, item-04-014, item-04-015, item-04-025]
    residual: {probability: Rare}
  - id: RISK-HAR-002
    category: safety
    status: proposed
    hazard: "A grader no one approved grades a candidate."
    situation: "Item verdicts arrive from a grader digest that is not, or is no longer, approved."
    harm: "A score no reviewed instrument produced enters the scoreboard."
    severity: Serious
    probability: Possible
    controls: [item-02-027, item-02-030]
    residual: {probability: Rare}
  - id: RISK-HAR-003
    category: safety
    status: proposed
    hazard: "An item identity is reused or carried by two scenarios."
    situation: "A deleted scenario's number is handed out again, or two scenarios carry one id."
    harm: "Cycle-to-cycle comparisons join different requirements, and a regression reads as a pass."
    severity: Serious
    probability: Unlikely
    controls: [item-01-003, item-02-034]
    residual: {probability: Rare}
  - id: RISK-HAR-004
    category: security
    stride: Tampering
    status: proposed
    hazard: "An instrument golden is moved to turn a failing item green."
    situation: "A golden is moved without a note, or for a reason outside the three the spec allows."
    harm: "The instrument stops measuring what the scenario states, unseen."
    severity: Serious
    probability: Unlikely
    controls: [item-03-003, item-03-004]
    residual: {probability: Rare}
---

# Risk register — pi harness

**Proposed, not yet approved.** The ways the harness could fail to perform
as intended, which make it high process risk in its validation
(CSV-HAR-001). Each is evaluated against RMP-HAR-001 and controlled by
requirements of the harness's own spec; a control counts once its
requirement is verified by a passing run, and the residual is then evaluated
again.
