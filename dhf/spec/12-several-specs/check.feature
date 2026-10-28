@UN-001
Feature: Spec check
  Checks each spec on its own terms.

  Rule: Each spec is checked on its own terms

    @spec-0387 @e2e
    Scenario: Each spec is checked on its own terms, and one spec's finding fails the check
      Given two specs, auth and infra, where infra's feature file names a need its needs.edn does not hold, and auth's names UN-001, which only auth's needs.edn holds
      When "rdm-spec check" is run
      Then it exits 1 with one finding, naming infra's feature file and the need, and none about auth
      And a need, a component and a context resolve within their own spec only
