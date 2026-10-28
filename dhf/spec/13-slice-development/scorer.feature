@UN-008
Feature: Scorer
  Scores a cycle that builds a slice.

  Rule: A cycle builds a slice

    @spec-0396 @e2e
    Scenario: A cycle may be scoped to a slice
      Given a configuration with :spec-layout :slices, and dhf/cycles/03/cycle.edn whose :scope is [{:slice "12-several-specs"}]
      When "rdm-spec checklist 03" is run
      Then it accepts the scope, and a slice the cut does not hold is refused with exit 2, naming it

  Rule: A slice cycle is scored for its slice, and for what it regressed

    @spec-0417 @e2e
    Scenario: A cycle names the slice it builds, and nothing else to scope it
      Given a configuration with :spec-layout :slices, and dhf/cycles/03/cycle.edn holding :slice "12-several-specs" and no :scope
      When "rdm-spec checklist 03" is run
      Then it reads the cycle as scoped to [{:slice "12-several-specs"}]
      And a cycle holding both :slice and :scope is refused with exit 2, naming both

    @spec-0418 @e2e
    Scenario: The score of a slice cycle counts the slice's items and the regression items apart
      Given a verified slice cycle 03 whose checklist holds 4 items of its slice and 6 regression items, and verdicts passing 3 of the slice's and all 6 regression items
      When "rdm-spec score 03 --verdicts V" is run
      Then dhf/cycles/03/score.edn holds :passed 9 and :scored 10, and :slice {:name "12-several-specs" :passed 3 :scored 4} and :regression {:passed 6 :scored 6}
