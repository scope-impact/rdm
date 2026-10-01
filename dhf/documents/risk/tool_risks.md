---
id: RMF-001
title: Risk register — RDM as a tool
kind: risk
risks:
  - id: RISK-TOOL-001
    hazard: "Tag discovery reads a design-input id from text that is not a tag."
    situation: "A test writes a fixture file naming DI-n, and no real test verifies DI-n."
    harm: "The matrix reports DI-n verified, and a team releases software whose requirement nobody tested."
    severity: Serious
    probability: Possible
    controls: [DI-40]
    residual: {probability: Rare}
  - id: RISK-TOOL-002
    hazard: "The release gate passes a design input whose test failed or never ran."
    situation: "A test fails, errors or is skipped in CI, and the release gate still reports PASSED."
    harm: "A team releases software with a requirement that is not verified."
    severity: Serious
    probability: Possible
    controls: [DI-3, DI-4]
    residual: {probability: Rare}
  - id: RISK-TOOL-003
    hazard: "Implementation lands before its design input is approved."
    situation: "An author or agent commits code while the design document that governs it is uncommitted or edited."
    harm: "The record shows a design approved after the code it governs; an audit finds design controls not followed."
    severity: Minor
    probability: Possible
    controls: [DI-2]
    residual: {probability: Unlikely}
  - id: RISK-TOOL-004
    hazard: "An agent changes the controlled record through RDM without review."
    situation: "An agent connected to rdm graph mcp issues a SPARQL Update or looks for a write tool."
    harm: "The record diverges from what reviewers approved, and evidence no one reviewed is presented as controlled."
    severity: Serious
    probability: Possible
    controls: [DI-42]
    residual: {probability: Rare}
  - id: RISK-TOOL-005
    hazard: "A tagged test passes without exercising every clause of its design input."
    situation: "A test asserts two of a design input's three clauses, and the pull-request reviewer does not notice."
    harm: "The design input is reported verified while one clause is untested, and the gap ships."
    severity: Serious
    probability: Possible
    controls: [DI-34]
    residual: {probability: Unlikely}
    acceptance:
      by: "RDM maintainers, by approving the pull request that records this register"
      rationale: "Whether a test proves its input is a reviewer's judgement. The mutation probe makes it checkable on demand; requiring it for every clause is the faithfulness gate Design Review 4 retired for costing more than it caught. Monitored by reviewers probing the tests a change touches."
---

# Risk register — RDM as a tool

RDM is not a medical device. Teams use it to keep and check the evidence for
one, so its risks are the ways it could misreport that evidence. Each control
is a design input of RDM's own record, verified by its tagged test; the
release gate holds this register to the rules in `design/risk.md` (DI-44).

Scored with the default matrix (the risk-analysis skill's four-by-four):
severity from the harm, probability from the situation, residual after the
controls. RISK-TOOL-005 stays at Medium after its control and is accepted
above, with the reason.
