# Component Checklist, container measurement: dhf/spec/model.edn.

@UN-008
Feature: Checklist
  Derives one item per stamped scenario, never written by hand.

  # The checklist is derived from the spec, never written by hand
  # (skallinen/loyly README.md@17d68d2, "What to steal" 1).
  Rule: The checklist is derived

    @spec-0019 @e2e
    Scenario: The checklist is derived from the spec, one item per stamped scenario
      Given a spec with 12 stamped scenarios, one of them tagged @wip
      When the checklist is derived
      Then it holds 12 items, each with the scenario's id, title, component and level
