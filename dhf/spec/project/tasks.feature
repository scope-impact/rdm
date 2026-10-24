# Component Tasks, container project: dhf/spec/model.edn.
#
# The one entry point a hook, CI, an agent, a person and the step definitions
# all use: the rdm-spec command, installed once, with nothing per project.
# Specs are written by editing files; the command only checks, cuts, fences,
# runs, ratifies, scores and publishes them.

@UN-009
Feature: Tasks
  The one command, rdm-spec, and its tasks.

  Rule: Everything runs through one command

    @spec-0176 @contract
    Scenario Outline: Each task is run as rdm-spec <task>
      Given a project set up by rdm-spec
      When "rdm-spec help" is run
      Then it lists the task <task>, described as <does>

      Examples:
        | task       | does                                                                      |
        | init       | write a new project's skeleton into a path that does not exist           |
        | adopt      | add the skeleton to an existing repository                                |
        | stamp      | give every unstamped scenario its id                                      |
        | check      | the spec check, the model lint, the views' freshness and the sources      |
        | views      | generate and render every view to dhf/views/                              |
        | cut        | tag the current merge on the default branch spec-N                        |
        | fence      | build a clean room from a cut, for the project, a container or a component |
        | run        | run the scenarios and write Allure results                                |
        | ratify     | run the declared test command in a clean room and judge the build         |
        | status     | each scenario's status from Allure results                                |
        | trace      | a need or a scenario with everything linked to it                         |
        | release    | the release check                                                         |
        | score      | score a cycle into dhf/cycles/NN/score.edn                                |
        | scoreboard | compare two cycles                                                        |
        | publish    | write the site and build it                                               |

  Rule: Every task reports the same way

    @spec-0177 @contract
    Scenario: A task that passes exits 0
      Given a spec that passes every check
      When "rdm-spec check" is run
      Then it exits 0

    @spec-0178 @contract
    Scenario: A task that finds a problem exits 1 and names it on the error stream
      Given a scenario with no level tag on line 12 of dhf/spec/spec/check.feature
      When "rdm-spec check" is run
      Then it exits 1
      And the error stream holds one line per finding, starting with the file and line, then the rule, then what is wrong

    @spec-0179 @contract
    Scenario: A task that cannot run exits 2 and says why
      Given the configured results directory does not exist
      When "rdm-spec status" is run
      Then it exits 2 and names the missing directory

    @spec-0180 @contract
    Scenario: An unknown task exits 2 and lists the tasks
      When "rdm-spec frobnicate" is run
      Then it exits 2, names frobnicate, and lists every task
