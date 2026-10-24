# Component Checklist, container measurement: dhf/spec/model.edn.

@UN-008
Feature: Checklist
  Derives one item per stamped scenario, never written by hand.

  # Incident: a scoring run whose every batch failed on a network outage once
  # committed "scored" over an empty record.
  Rule: The instrument refuses to record what it did not measure

    @spec-0019 @e2e
    Scenario: The checklist is derived from the spec, one item per stamped scenario
      Given a spec with 12 stamped scenarios, one of them tagged @wip
      When the checklist is derived
      Then it holds 12 items, each with the scenario's id, title, component and level
