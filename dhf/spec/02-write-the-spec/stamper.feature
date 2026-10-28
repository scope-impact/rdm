# Component Stamper, bounded context spec of the rdm-spec container: dhf/spec/model.edn.

@UN-001
Feature: Stamper
  Hands out scenario ids from the ledger, never twice.

  Rule: The stamper hands out each id once, from the ledger

    @spec-0054 @e2e
    Scenario: The stamper gives every unstamped scenario the next id from the ledger
      Given the ledger records 41 as the highest id issued
      And a scenario in the spec carries no id tag
      When "rdm-spec stamp" is run
      Then the scenario is tagged @spec-0042
      And the ledger records 42 as the highest id issued

    @spec-0055 @e2e
    Scenario: Stamping twice changes nothing
      Given every scenario in the spec carries an id
      When "rdm-spec stamp" is run
      Then no file in the spec changes
      And the ledger does not change

    @spec-0056 @e2e
    Scenario: A deleted scenario's id is never issued again
      Given the ledger records 42 as the highest id issued
      And the scenario tagged @spec-0042 has been deleted
      And a new scenario carries no id tag
      When "rdm-spec stamp" is run
      Then the new scenario is tagged @spec-0043

    @spec-0057 @e2e
    Scenario: The id does not depend on the wording or the location
      Given a scenario tagged @spec-0007 in one component's feature file
      When its title is reworded and it is moved to another component's feature file
      And "rdm-spec stamp" is run
      Then it is still tagged @spec-0007

    @spec-0058 @e2e
    Scenario: The id prefix is configuration
      Given the project's configuration sets the id prefix to "loyly"
      And a scenario in the spec carries no id tag
      When "rdm-spec stamp" is run
      Then the scenario is tagged with an id starting @loyly-
