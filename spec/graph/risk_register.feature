Feature: Risk register
  A team can keep its risk register in the record, each risk scored from its own matrix and each control a verified requirement, and cannot release while a risk is unscored, uncontrolled, or residually unacceptable without a named acceptance.

  @item-06-008
  Scenario: RDM shall project the risk register into a risks graph — each risk with its category, STRIDE category, linked risks, hazard, situation, harm, scores, initial and residual level, residual decision (acceptable, accepted, needs acceptance, unacceptable, or not evaluated while a control lacks a passing test), status, controlling requirements and acceptance — with SHACL shapes that block exactly the risks, and the register findings, the release gate blocks and warn on a risk still proposed, and the agent server's trace shall accept a risk id and list, for a requirement, the risks it controls.
