# Component Scorer, container measurement: dhf/spec/model.edn.

@UN-008
Feature: Scorer
  Files a cycle's score, and nothing when nothing was measured.

  Rule: A cycle is one directory, declared before it is scored

    @spec-0173 @contract
    Scenario: A cycle is declared in dhf/cycles/NN/cycle.edn
      Given dhf/cycles/03/cycle.edn holds the cut spec-3, the scope container verification, the builder builder-a as an agent, the spec owner Sami as a human, and the results directory
      When "rdm-spec score 03" is run
      Then the cycle is scored over those results, for that cut and scope

    @spec-0174 @contract
    Scenario: The score is written beside the cycle
      Given cycle 03 scored with 11 of 12 scenarios passing
      When "rdm-spec score 03" is run
      Then dhf/cycles/03/score.edn holds each scenario's id and verdict, the 11 passed and the 12 scored

  # Incident: a scoring run whose every batch failed on a network outage once
  # committed "scored" over an empty record (skallinen/loyly
  # harness/README.md@17d68d2, "The failure modes this shape was learned from").
  Rule: The instrument refuses to record what it did not measure

    @spec-0022 @e2e
    Scenario: Scoring with no results records nothing
      Given a cycle whose results directory holds no Allure result
      When "rdm-spec score 03" is run
      Then it exits 2, writes nothing, and says no measurement was made

    @spec-0023 @e2e
    Scenario: An unverified item is scored as a fail
      Given a checklist of 12 items
      And results for 11 of them, all passed
      When "rdm-spec score 03" is run
      Then the score is 11 of 12

  # The build under test never judges itself. Ratify and score run from the
  # rdm-spec installed outside the clean room.
  Rule: The build under test never scores itself

    @spec-0175 @e2e
    Scenario: The installed rdm-spec scores, never the regenerated one
      Given a clean room whose regenerated build includes its own rdm-spec
      When "rdm-spec score 03" is run
      Then the installed rdm-spec writes the score
      And the regenerated build's own scorer is never run

  # Every role may be a human or an agent (UN-012), so the instrument cannot
  # rely on "a person" to stay out of the loop. What it relies on is that the
  # one who reads the score is not the one who built what was scored
  # (skallinen/loyly harness/README.md@17d68d2, "The idea in five lines").
  Rule: The builder never reads its own score

    @spec-0142 @e2e @UN-012
    Scenario: A cycle records who built it and who reads its score, each as human or agent
      Given cycle 03 declares its builder as an agent named builder-a and its spec owner as a human named Sami
      When "rdm-spec score 03" is run
      Then score.edn names builder-a as an agent builder and Sami as a human spec owner

    @spec-0143 @e2e @UN-012
    Scenario: A cycle whose builder is also its spec owner is refused
      Given cycle 03 declares builder-a as both its builder and its spec owner
      When "rdm-spec score 03" is run
      Then it exits 1, writes nothing, and says the builder may not read its own score

    @spec-0144 @e2e @UN-012
    Scenario: A cycle that names no builder or no spec owner is refused
      Given cycle 03 declares a builder and no spec owner
      When "rdm-spec score 03" is run
      Then it exits 1, writes nothing, and names the missing role
