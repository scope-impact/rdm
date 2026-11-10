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
  - id: RISK-TOOL-008
    category: safety
    status: proposed
    hazard: "The spec is read short: requirements are left out and no one notices."
    situation: "A context's spec is missing from the repository (ignored by git, or a plan link to a folder never committed), and the gate counts only what it found."
    harm: "A release passes with requirements that were never checked, reported as complete."
    severity: Serious
    probability: Possible
    controls: [item-04-001, item-01-001]
    residual: {probability: Rare}
  - id: RISK-TOOL-009
    category: safety
    status: proposed
    hazard: "The evidence that a release was verified cannot be retrieved later."
    situation: "The CI artifact holding the evidence bundle expires, or the repository is lost."
    harm: "An auditor cannot confirm what was verified for a release, and the team cannot show it."
    severity: Serious
    probability: Unlikely
    controls: [item-04-003]
    residual: {probability: Unlikely}
    acceptance:
      by: "RDM maintainers, by approving the pull request that records this register"
      rationale: "RDM writes the complete, checksummed bundle; keeping it for the retention period is document control's job (DC-001, 11.10(c)), not the tool's. Monitored through DC-001's retention commitment."
  - id: RISK-TOOL-010
    category: safety
    status: proposed
    hazard: "The gate decides on results that are not the evidence of the commit being released."
    situation: "Results are altered or swapped on their way from the test job to the gate, or an earlier run's results are used."
    harm: "A release is approved on evidence of something else."
    severity: Serious
    probability: Unlikely
    controls: [item-06-016, item-07-009, item-04-003]
    residual: {probability: Rare}
---

# Risk register — RDM as a tool

**Proposed, not yet approved.** An agent wrote these ratings and the
acceptance of RISK-TOOL-005; a maintainer approves each by reviewing it and
setting `status: approved` (or changing it).

RDM is not a medical device. Teams use it to keep and check the evidence for
one, so its risks are the ways it could misreport that evidence. Each control
is a requirement of RDM's own spec (`.pi/spec/`), verified by the tests that
claim it; the
release gate holds this register to the rules in `design/risk.md` (item-02-002).

Evaluated against RDM's risk policy (`policy.md`, RMP-001):
severity from the harm, probability from the situation, residual after the
controls. RISK-TOOL-005 has no control RDM can give it: it stays at Medium
and is accepted above, with the reason.
