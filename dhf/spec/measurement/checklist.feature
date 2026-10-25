# Component Checklist, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.

@UN-008
Feature: Checklist
  Derives one item per stamped scenario at the cycle's cut, never written by
  hand, for a grader to grade.

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

    @spec-0210 @contract
    Scenario: checklist.json has one shape
      Given cycle 03 of cut spec-3, with one item to grade and one planned scenario
      When "rdm-spec checklist 03" is run
      Then dhf/cycles/03/checklist.json holds:
        """
        {"cycle": "03",
         "cut": "spec-3",
         "items": [{"id": "spec-0012", "title": "A view with no description fails the lint",
                    "context": "architecture", "component": "Lint", "level": "e2e"}],
         "planned": [{"id": "spec-0013", "title": "A component that owns no feature file fails the lint"}]}
        """
      And its keys appear in that order, items and planned sorted by id

  # A scenario the checklist cannot name would shrink the denominator without
  # anyone noticing (loyly:harness/spec.feature@17d68d2, "An unstamped scenario
  # stops the checklist rather than shrinking it").
  Rule: An unstamped scenario stops the checklist

    @spec-0211 @e2e
    Scenario: A cut holding an unstamped scenario has no checklist
      Given the spec at cut spec-3 holds a scenario with no id
      When "rdm-spec checklist 03" is run
      Then it exits 1, writes nothing, and names the file and line of the unstamped scenario

  # A clause named is not a clause met (dhf/wiki/pages/regulated-profile.md,
  # "Referenced is not met"). A grader judges it as a scenario is judged, and
  # an ungraded clause is a fail like any ungraded item.
  @UN-013
  Rule: Under the regulated profile, every clause is an item to grade

    @spec-0266 @contract
    Scenario: Each clause of the cut's clause lists is an item after the scenarios
      Given cycle 03 of cut spec-3 in a regulated project whose clause list dhf/clauses/std.txt at the cut holds "STD:5.1 Development plan"
      When "rdm-spec checklist 03" is run
      Then the items of dhf/cycles/03/checklist.json end with:
        """
        {"id": "STD:5.1", "title": "Development plan",
         "context": "compliance", "component": "dhf/clauses/std.txt", "level": "clause"}
        """
      And the clause items come after every scenario item, in the clause lists' order with includes in place, each key once
