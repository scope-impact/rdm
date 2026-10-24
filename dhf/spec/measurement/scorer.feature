# Component Scorer, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
#
# Scoring is independent of the builder's tests, as in Löyly: a grader, human
# or agent and never the builder, grades each checklist item against the
# ratified build, however they grade, and the scorer files the verdicts
# (loyly:README.md@17d68d2, "3. Measure").

@UN-008
Feature: Scorer
  Files a grader's verdicts against the checklist, and nothing when nothing was graded.

  Rule: A cycle is one directory, declared before it is ratified

    @spec-0173 @contract
    Scenario: A cycle is declared by hand in dhf/cycles/NN/cycle.edn
      Given dhf/cycles/03/cycle.edn holds:
        """
        {:cut "spec-3"
         :scope {:context :verification}
         :builder {:name "builder-a" :actor :agent}
         :grader {:name "grader-b" :actor :agent}
         :spec-owner {:name "Sami" :actor :human}}
        """
      When "rdm-spec checklist 03" and "rdm-spec ratify /tmp/room 03" are run
      Then both work on the cut spec-3, for that scope

    @spec-0200 @contract
    Scenario Outline: A scope is the project, one context or one component
      Given dhf/cycles/03/cycle.edn holds the scope <scope>
      When "rdm-spec checklist 03" is run
      Then it accepts the scope

      Examples:
        | scope                          |
        | :project                       |
        | {:context :verification}       |
        | {:component :spec/parser}      |

    @spec-0174 @contract
    Scenario: The score is written beside the cycle
      Given cycle 03's checklist holds 12 items
      And a verdicts file grading 11 of them passed and 1 failed
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then dhf/cycles/03/score.edn holds each item's id and verdict, the 11 passed, the 12 scored, and the cycle's builder, grader, spec owner and scope

  # Incident: a scoring run whose every batch failed on a network outage once
  # committed "scored" over an empty record (loyly:harness/README.md@17d68d2,
  # "The failure modes this shape was learned from").
  Rule: The instrument refuses to record what it did not measure

    @spec-0022 @e2e
    Scenario: Zero verdicts records nothing
      Given a verdicts file with no verdict in it
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then it exits 2, writes nothing, and says the grading did not happen

    @spec-0023 @e2e
    Scenario: An item nobody graded is scored as a fail
      Given cycle 03's checklist holds 12 items
      And a verdicts file grading 11 of them, all passed
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then the score is 11 of 12, and the ungraded item is recorded as failed

    @spec-0201 @e2e
    Scenario: A verdict for an item not on the checklist is refused
      Given a verdicts file grading spec-0999, which is not on cycle 03's checklist
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then it exits 1, writes nothing, and names spec-0999

  # Every role may be a human or an agent (UN-012), so the instrument cannot
  # rely on "a person" to stay out of the loop. What it relies on is that the
  # one who grades and the one who reads the score are not the one who built
  # (loyly:harness/README.md@17d68d2, "The idea in five lines").
  Rule: The builder neither grades nor reads its own score

    @spec-0142 @e2e @UN-012
    Scenario: A cycle records who built, graded and reads it, each as human or agent
      Given cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then score.edn names each of them with their role and whether human or agent

    @spec-0143 @e2e @UN-012
    Scenario: A cycle whose builder is also its grader or spec owner is refused
      Given cycle 03 declares builder-a as both its builder and its grader
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then it exits 1, writes nothing, and says the builder may not grade or read its own score

    @spec-0144 @e2e @UN-012
    Scenario: A cycle that leaves a role unnamed is refused
      Given cycle 03 declares a builder and a grader and no spec owner
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then it exits 1, writes nothing, and names the missing role
