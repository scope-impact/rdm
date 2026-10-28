# Component Checklist, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
# Slice 10-regulated-profile: the checklist's regulated rule (ADR 0004).

@UN-013
Feature: Checklist
  Under the regulated profile, every declared clause is an item to grade.

  # A clause named is not a clause met (dhf/wiki/pages/regulated-profile.md,
  # "Referenced is not met"). A grader judges it as a scenario is judged, and
  # an ungraded clause is a fail like any ungraded item.
  @UN-013
  Rule: Under the regulated profile, every clause is an item to grade

    @spec-0266 @contract
    Scenario: Each clause of the cut's clause lists is an item after the scenarios
      Given cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std.txt at the cut holds "STD:5.1 Development plan"
      When "rdm-spec checklist 03" is run
      Then the items of dhf/cycles/03/checklist.json end with:
        """
        {"id": "STD:5.1", "title": "Development plan",
         "context": "compliance", "component": "dhf/clauses/std.txt", "level": "clause"}
        """
      And the clause items come after every scenario item, in the clause lists' order with includes in place, each key once

    @spec-0271 @e2e
    Scenario: A cycle scoped away from the compliance context carries no clause item
      Given cycle 03 of cut spec-3, scoped to the context architecture, in a regulated project whose clause list at the cut holds clauses
      When "rdm-spec checklist 03" is run
      Then dhf/cycles/03/checklist.json holds no item at the level clause
