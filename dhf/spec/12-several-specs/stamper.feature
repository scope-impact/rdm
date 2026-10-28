@UN-001
Feature: Stamper
  Stamps each spec from its own ledger.

  Rule: Each spec stamps from its own ledger

    @spec-0388 @e2e
    Scenario: Each spec stamps from its own ledger, with its own prefix
      Given two specs, auth with 3 stamped scenarios and infra with none, each with one unstamped scenario
      When "rdm-spec stamp" is run
      Then auth's is stamped @auth-0004 from dhf/sdd/auth/ids.edn, and infra's @infra-0001 from dhf/sdd/infra/ids.edn
