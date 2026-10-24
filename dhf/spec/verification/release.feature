# Component Release check, container verification: dhf/spec/model.edn.

@UN-004
Feature: Release check
  Refuses a release while any scenario is not verified.

  Rule: Planned work is a scenario tagged @wip

    @spec-0059 @e2e
    Scenario: The release check refuses while any scenario is tagged @wip
      Given a stamped scenario tagged @wip
      When the release check runs
      Then it fails and names the scenario's id
