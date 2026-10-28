@UN-007
Feature: Verify
  Requires the results of the specs a cycle is scoped to.

  Rule: A spec-scoped verify requires that spec's results

    @spec-0410 @e2e
    Scenario: A cycle scoped to one spec is verified on that spec's scenarios
      Given two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is [{:spec "auth"}], and a room whose tests write a passing result for every scenario of auth and none of infra
      When "rdm-spec verify ROOM 03" is run
      Then the tests check passes, counting the scenarios of auth only
      And with one of auth's results missing, it fails, naming that scenario
