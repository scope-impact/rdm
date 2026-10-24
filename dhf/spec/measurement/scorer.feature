# Component Scorer, container measurement: dhf/spec/model.edn.

@UN-008
Feature: Scorer
  Files a cycle's score, and nothing when nothing was measured.

  # Incident: a scoring run whose every batch failed on a network outage once
  # committed "scored" over an empty record.
  Rule: The instrument refuses to record what it did not measure

    @spec-0022 @e2e
    Scenario: Scoring with no results records nothing
      Given a cycle with no Allure results
      When the cycle is scored
      Then nothing is written
      And the scorer exits non-zero and says no measurement was made

    @spec-0023 @e2e
    Scenario: An unverified item is scored as a fail
      Given a checklist of 12 items
      And results for 11 of them, all passed
      When the cycle is scored
      Then the score is 11 of 12

  Rule: The previous release judges the next

    @spec-0024 @e2e
    Scenario: Regenerated RDM is scored by the pinned release
      Given the configuration pins RDM release 0.4.0 as the judge
      And a regenerated RDM that has been ratified
      When the cycle is scored
      Then release 0.4.0 runs the scorer
      And the regenerated build's own scorer is never run
