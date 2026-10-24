# Component Scorer, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.

@UN-008
Feature: Scorer
  Files a cycle's score, and nothing when nothing was measured.

  Rule: A cycle is one directory, declared before it is scored

    @spec-0173 @contract
    Scenario: A cycle is declared in dhf/cycles/NN/cycle.edn
      Given dhf/cycles/03/cycle.edn holds:
        """
        {:cut "spec-3"
         :scope {:context :verification}
         :builder {:name "builder-a" :actor :agent}
         :spec-owner {:name "Sami" :actor :human}
         :results "dhf/cycles/03/results"}
        """
      When "rdm-spec score 03" is run
      Then the cycle is scored over those results, against the spec at spec-3, for that scope

    @spec-0174 @contract
    Scenario: The score is written beside the cycle
      Given cycle 03 scored with 11 of 12 scenarios passing
      When "rdm-spec score 03" is run
      Then dhf/cycles/03/score.edn holds each scored scenario's id and verdict (passed or failed), the 11 passed, the 12 scored, the cycle's builder and spec owner, and its scope

  # Incident: a scoring run whose every batch failed on a network outage once
  # committed "scored" over an empty record (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
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

  # Every role may be a human or an agent (UN-012), so the instrument cannot
  # rely on "a person" to stay out of the loop. What it relies on is that the
  # one who reads the score is not the one who built what was scored
  # (loyly:harness/README.md@17d68d2, "The idea in five lines").
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
