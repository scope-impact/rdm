# Component Oracle, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
#
# rdm-spec regenerates its own instrument every cycle, so the instrument needs
# a judge of its own: recorded fixture cycles and the outputs the instrument
# must give on them (cycle 03, dhf/cycles/03/proposed-spec-changes.md, C12;
# loyly:harness/README.md@17d68d2, "The pieces, in pipeline order").

@UN-008
Feature: Oracle
  Runs the checklist, the scorer and the scoreboard on recorded fixture cycles
  and compares what they produce with recorded goldens.

  # The layout was never written down, and the oracle never enters a clean
  # room, so cycle 08's builder invented one and its oracle refused this
  # repository's own goldens (dhf/cycles/08/proposed-spec-changes.md, 1).
  Rule: The oracle's directory has one layout

    @spec-0276 @contract
    Scenario: The oracle reads one fixture project, its cycles and their goldens
      Given dhf/harness/oracle/ holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each
      When "rdm-spec oracle" is run
      Then it reads dhf/harness/oracle/ as:
        | path                                  | holds                                                        |
        | project/                              | the one fixture project, a git repository whose cuts the cycles name |
        | cycles/NN/cycle.edn                   | the fixture cycle NN's record                                |
        | cycles/NN/ratified.edn                | its ratification                                             |
        | cycles/NN/verdicts.edn                | its verdicts                                                 |
        | goldens/NN/checklist.json             | the checklist the instrument must derive for cycle NN        |
        | goldens/NN/score.edn                  | the score it must file for cycle NN                          |
        | goldens/scoreboard-NN-MM.edn          | the scoreboard it must give from cycle NN to cycle MM        |
        | RECORDED.md                           | each recording, its date and its reason                      |
      And it exits 0 and names each golden it compared

    @spec-0277 @e2e
    Scenario: An oracle directory that holds no fixture cycle fails
      Given a project whose dhf/harness/oracle/ holds only RECORDED.md
      When "rdm-spec oracle" is run
      Then it exits 1 and says the oracle holds no fixture cycle, so it judged nothing

  Rule: The instrument is judged against recorded goldens

    @spec-0228 @e2e
    Scenario: The oracle passes when every output matches its golden
      Given dhf/harness/oracle/ holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each
      When "rdm-spec oracle" is run
      Then it exits 0 and names each golden it compared

    @spec-0229 @e2e
    Scenario: An output that differs from its golden fails the oracle, naming the difference
      Given dhf/harness/oracle/ holds a golden score whose second item says pass where the scorer files fail
      When "rdm-spec oracle" is run
      Then it exits 1 and names the golden, the item and both values

    # Re-recording to get a green destroys the only evidence that anything
    # moved (loyly:harness/README.md@17d68d2, "What makes the checks run").
    @spec-0230 @e2e
    Scenario: Recording goldens says which changed, and why
      Given dhf/harness/oracle/ holds goldens, one of which the instrument no longer reproduces
      When "rdm-spec oracle record --reason 'the scoreboard now says the denominator moved'" is run
      Then it rewrites that golden, names it, and appends the reason with the date to dhf/harness/oracle/RECORDED.md

    @spec-0231 @e2e
    Scenario: Recording without a reason is refused
      Given dhf/harness/oracle/ holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each
      When "rdm-spec oracle record" is run
      Then it exits 2, says a reason is required, and rewrites nothing

  # A room built, or a build ratified, by an instrument nobody checked hands
  # the next cycle a judge nobody checked (loyly:harness/README.md@17d68d2,
  # "What makes the checks run").
  # The oracle is a gate stage of fence and ratify: the command runs it first
  # and stops the task when it has findings, so neither fence nor ratify knows
  # the oracle (dhf/spec/kernel/command.feature, "A gate stops the task").
  Rule: Nothing is fenced or ratified while the oracle is red

    @spec-0232 @e2e
    Scenario Outline: Fence and ratify refuse while the oracle fails
      Given a project whose dhf/harness/oracle/ holds a golden the instrument does not reproduce
      When "rdm-spec <task>" is run
      Then it exits 1, prints the oracle's findings unchanged, and <does-nothing>

      Examples:
        | task                  | does-nothing                       |
        | fence ROOM spec-3     | builds no room                     |
        | ratify ROOM 03        | writes no ratified.edn             |

    @spec-0233 @e2e
    Scenario: A project with no oracle is not refused
      Given a project with no dhf/harness/oracle/
      When "rdm-spec oracle" is run
      Then it exits 0 and says there is no oracle to run
