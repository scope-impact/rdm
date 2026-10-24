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
    Scenario: Regenerated rdm-spec is scored by the pinned release
      Given the configuration pins rdm-spec release 0.4.0 as the judge
      And a regenerated rdm-spec that has been ratified
      When the cycle is scored
      Then release 0.4.0 runs the scorer
      And the regenerated build's own scorer is never run

  # Every role may be a human or an agent (UN-012), so the instrument cannot
  # rely on "a person" to stay out of the loop. What it relies on is that the
  # one who reads the score is not the one who built what was scored
  # (skallinen/loyly harness/README.md@17d68d2, "The idea in five lines").
  Rule: The builder never reads its own score

    @spec-0142 @e2e @UN-012
    Scenario: A cycle records who built it and who reads its score, each as human or agent
      Given cycle 03 declares its builder as an agent named builder-a and its spec owner as a human named Sami
      When cycle 03 is scored
      Then the cycle record names builder-a as an agent builder and Sami as a human spec owner

    @spec-0143 @e2e @UN-012
    Scenario: A cycle whose builder is also its spec owner is refused
      Given cycle 03 declares builder-a as both its builder and its spec owner
      When cycle 03 is scored
      Then nothing is written
      And the scorer says the builder may not read its own score

    @spec-0144 @e2e @UN-012
    Scenario: A cycle that names no builder or no spec owner is refused
      Given cycle 03 declares a builder and no spec owner
      When cycle 03 is scored
      Then nothing is written
      And the scorer names the missing role
