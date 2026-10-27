# Component Scorer, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
#
# Scoring is independent of the builder's tests, as in Löyly: a grader, human
# or agent and never the builder, grades each checklist item against the
# verified build, however they grade, and the scorer files the verdicts
# (loyly:README.md@17d68d2, "3. Measure").

@UN-008
Feature: Scorer
  Files a grader's verdicts against the checklist, and nothing when nothing was graded.

  Rule: A cycle is one directory, declared before it is verified

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
      When "rdm-spec checklist 03" and "rdm-spec verify ROOM 03" are run
      Then both work on the cut spec-3, and the checklist holds the items of that scope

    @spec-0200 @contract
    Scenario Outline: A scope is the project, one context or one component, or a list of contexts and components
      Given dhf/cycles/03/cycle.edn holds the scope <scope>
      When "rdm-spec checklist 03" is run
      Then it accepts the scope

      Examples:
        | scope                          |
        | :project                       |
        | {:context :verification}       |
        | {:component :spec/parser}      |
        | [{:context :knowledge} {:component :spec/parser}] |

    @spec-0174 @contract
    Scenario: The score is written beside the cycle
      Given cycle 03's checklist holds 2 items and its verification passed on the room tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904
      And a verdicts file grading spec-0012 pass and spec-0014 fail
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then dhf/cycles/03/score.edn holds:
        """
        {:cycle "03"
         :cut "spec-3"
         :scope :project
         :room-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
         :builder {:name "builder-a" :actor :agent}
         :grader {:name "grader-b" :actor :agent}
         :spec-owner {:name "Sami" :actor :human}
         :items [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
                 {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}]
         :passed 1
         :scored 2}
        """

  Rule: Verdicts have one shape

    @spec-0212 @contract
    Scenario: A verdicts file is a vector of id, verdict and evidence
      Given a verdicts file holding:
        """
        [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
         {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}]
        """
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then both verdicts are filed

    # spec-28: two graders grade one cycle in two rooms; their files are scored
    # as one. A file that is not readable EDN names where it stops.
    @spec-0379 @e2e
    Scenario: The verdicts of several files are filed as one
      Given two verdicts files, one grading spec-0012 and spec-0013, the other spec-0014
      When "rdm-spec score 03 --verdicts a.edn --verdicts b.edn" is run
      Then all three verdicts are filed in one score.edn

    @spec-0380 @e2e
    Scenario Outline: Verdicts across files are held to the same rules as one file
      Given two verdicts files where <wrong>
      When "rdm-spec score 03 --verdicts a.edn --verdicts b.edn" is run
      Then it exits 1, writes nothing, and says <says>

      Examples:
        | wrong                                                         | says                                                         |
        | both grade spec-0012                                          | spec-0012 is graded twice, naming both files                 |
        | b.edn's third line holds a string with an escape EDN refuses  | b.edn is not readable EDN, naming its line, as FILE:LINE     |

    @spec-0213 @e2e
    Scenario Outline: A malformed verdicts file is refused and nothing is written
      Given a verdicts file that <is malformed>
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then it exits 1, writes nothing, and says what is malformed

      Examples:
        | is malformed                                  |
        | is a map, not a vector                         |
        | grades spec-0012 twice                         |
        | gives spec-0012 the verdict :maybe             |
        | gives spec-0012 no evidence                    |

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
      And score.edn holds that item as {:id ID :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}

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

  Rule: Only a verified build is scored

    @spec-0214 @e2e
    Scenario Outline: A cycle whose verification did not pass is not scored
      Given dhf/cycles/03/ratified.edn <state>
      When "rdm-spec score 03 --verdicts verdicts.edn" is run
      Then it exits 1, writes nothing, and says the cycle has no passing verification

      Examples:
        | state                     |
        | holds :outcome :refused   |
        | does not exist            |
