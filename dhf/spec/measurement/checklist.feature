# Component Checklist, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.

@UN-008
Feature: Checklist
  Derives one item per stamped scenario, never written by hand.

  # The checklist is derived from the spec, never written by hand
  # (loyly:README.md@17d68d2, "What to steal" 1).
  Rule: The checklist is derived

    @spec-0019 @e2e
    Scenario: The checklist is derived from the spec, one item per stamped scenario
      Given the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip
      When "rdm-spec score 03" is run for a cycle of cut spec-3
      Then dhf/cycles/03/checklist.json holds 11 items, each with the scenario's id, title, context, component and level
      And it lists the @wip scenario as planned and does not score it
