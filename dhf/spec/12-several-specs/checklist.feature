@UN-008
Feature: Checklist
  Derives a checklist of one spec, or of every spec.

  Rule: A cycle may be scoped to one spec

    @spec-0389 @e2e
    Scenario: A cycle may be scoped to one spec
      Given two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is [{:spec "auth"}]
      When "rdm-spec checklist 03" is run, and verify's tests check reads the cycle's results
      Then the checklist holds one item per stamped scenario of auth and none of infra
      And verify requires a result for every scenario of auth, and none for infra's
