---
id: RMF-001
title: Risk register — RDM as a tool
kind: risk
status: proposed
risks:
  - id: RISK-TOOL-002
    category: safety
    status: proposed
    hazard: "The release gate passes a requirement whose test failed or never ran."
    situation: "A test fails, errors or is skipped in CI, and the release gate still reports PASSED."
    harm: "A team releases software with a requirement that is not verified."
    severity: Serious
    probability: Possible
    controls: [item-04-001, item-04-002]
    residual: {probability: Rare}
  - id: RISK-TOOL-004
    category: security
    stride: Tampering
    status: proposed
    hazard: "An agent changes the controlled record through RDM without review."
    situation: "An agent connected to rdm graph mcp issues a SPARQL Update or looks for a write tool."
    harm: "The record diverges from what reviewers approved, and evidence no one reviewed is presented as controlled."
    severity: Serious
    probability: Possible
    controls: [item-06-007]
    residual: {probability: Rare}
  - id: RISK-TOOL-005
    category: safety
    status: proposed
    hazard: "A test that claims a requirement passes without checking everything its scenario states."
    situation: "A test checks two of the three things a requirement requires, and the pull-request reviewer does not notice."
    harm: "The requirement is reported verified while one thing it requires is unchecked, and the gap ships."
    severity: Serious
    probability: Possible
    controls: []
    acceptance:
      by: "RDM maintainers, by approving the pull request that records this register"
      rationale: "Whether a test proves its requirement is a reviewer's judgement, made in the pull-request review; no gate can make it. Monitored by reviewers reading each changed test against its scenario's text."
  - id: RISK-TOOL-006
    category: security
    stride: Tampering
    status: proposed
    hazard: "The served graph accepts SPARQL updates from any web origin."
    situation: "A person browses the graph in Graph Explorer while a web page they have open posts an update to the local endpoint."
    harm: "The graph the person reviews is cleared or shows forged verification, and they act on evidence the record does not hold."
    severity: Serious
    probability: Unlikely
    controls: [item-06-002]
    residual: {probability: Rare}
  - id: RISK-TOOL-007
    category: security
    stride: Information disclosure
    status: proposed
    hazard: "A read-only query can make RDM send network requests."
    situation: "A prompt-injected agent, or a web page posting to the served endpoint, sends a query with a SERVICE clause naming an internal or external address."
    harm: "The server probes or reaches services from the user's machine, outside the review the record is under."
    severity: Serious
    probability: Unlikely
    controls: [item-06-007, item-06-002]
    residual: {probability: Rare}
---

# Risk register — RDM as a tool

**Proposed, not yet approved.** An agent wrote these ratings and the
acceptance of RISK-TOOL-005; a maintainer approves each by reviewing it and
setting `status: approved` (or changing it).

RDM is not a medical device. Teams use it to keep and check the evidence for
one, so its risks are the ways it could misreport that evidence. Each control
is a requirement of RDM's own spec (`spec/`), verified by the tests that
claim it; the
release gate holds this register to the rules in `design/risk.md` (item-02-002).

Evaluated against RDM's risk policy (`policy.md`, RMP-001):
severity from the harm, probability from the situation, residual after the
controls. RISK-TOOL-005 has no control RDM can give it: it stays at Medium
and is accepted above, with the reason.
