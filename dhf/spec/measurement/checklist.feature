# Component Checklist, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.

@UN-008
Feature: Checklist
  Derives one item per stamped scenario, never written by hand.

  # The checklist is derived from the spec, never written by hand
  # (loyly:README.md@17d68d2, "What to steal" 1).
  Rule: The checklist is derived from the cycle's cut

    @spec-0019 @e2e
    Scenario: The checklist is derived from the spec at the cut, one item per stamped scenario
      Given the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip
      And dhf/cycles/03/cycle.edn names the cut spec-3
      When "rdm-spec checklist 03" is run
      Then dhf/cycles/03/checklist.json holds 11 items to grade, each with the scenario's id, title, context, component and level
      And it lists the @wip scenario separately, as planned
