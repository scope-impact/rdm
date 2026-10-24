# Component Scoreboard, container measurement: dhf/spec/model.edn.

@UN-008
Feature: Scoreboard
  Compares cycles honestly, for the spec owner, never the builder.

  Rule: The scoreboard compares cycles honestly

    @spec-0020 @e2e
    Scenario: Both directions are counted, never the net
      Given cycle 01 and cycle 02 scored over the same 12 items
      And 3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01
      When "rdm-spec scoreboard 01 02" is run
      Then it reports 3 got better and 1 got worse
      And it reports no net figure

    @spec-0021 @e2e
    Scenario: A moved denominator is said out loud
      Given cycle 01 scored 12 items and cycle 02 scored 15
      When "rdm-spec scoreboard 01 02" is run
      Then it compares only the 12 items both scored
      And it states that the denominator moved from 12 to 15
      And it counts the 3 new items separately

  # A partial regeneration can break what it did not rebuild; the scoreboard
  # shows that apart from what it did.
  Rule: A scoped cycle is reported target first

    @spec-0172 @e2e
    Scenario: The target's scenarios are reported apart from the rest
      Given cycle 02 regenerated the container verification and cycle 01 is its baseline
      When "rdm-spec scoreboard 01 02" is run
      Then it reports better and worse for verification's scenarios first
      And then, separately, better and worse for every other scenario
