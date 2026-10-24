# Component Tasks, bounded context project of the rdm-spec container: dhf/spec/model.edn.
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
      Then it lists the task <task> with its usage <usage>, described as <does>

      Examples:
        | task       | usage                                                    | does                                                                 |
        | init       | init PATH [--id-prefix P]                                | write the skeleton into a path that does not exist                   |
        | adopt      | adopt                                                    | add the skeleton to the current repository, overwriting nothing      |
        | stamp      | stamp                                                    | give every unstamped scenario its id                                 |
        | check      | check [--sources DIR] [--events]                         | the spec check, the model lint, the views' freshness, sources, wiki  |
        | views      | views                                                    | generate and render every view to dhf/views/                         |
        | cut        | cut                                                      | tag the current merge on the default branch spec-N                   |
        | fence      | fence ROOM CUT [--context C \| --component C/N]          | build a clean room from a cut                                        |
        | run        | run [--results DIR]                                      | run the scenarios and write Allure results                           |
        | ratify     | ratify ROOM CYCLE                                        | judge the room's implementation against its cycle's cut              |
        | status     | status [--results DIR]                                   | each scenario's status                                               |
        | trace      | trace ID [--results DIR]                                 | a need or a scenario with everything linked to it                    |
        | release    | release [--results DIR]                                  | the release check                                                    |
        | score      | score CYCLE                                              | score a cycle into dhf/cycles/CYCLE/                                 |
        | scoreboard | scoreboard CYCLE CYCLE                                   | compare two cycles                                                   |
        | publish    | publish [--results DIR]                                  | write the site and build it                                          |

    @spec-0193 @contract
    Scenario: A task's results directory defaults to the configured one
      Given the configured results directory is dhf/allure-results
      When "rdm-spec status" is run with no --results
      Then it reads dhf/allure-results

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
      And the error stream holds one line per finding: "FILE:LINE: RULE: what is wrong", FILE and LINE left out where none applies
      And the standard output holds only the task's result, as EDN

    @spec-0179 @contract
    Scenario: A task that cannot run exits 2 and says why
      Given the configured results directory does not exist
      When "rdm-spec status" is run
      Then it exits 2 and names the missing directory

    @spec-0180 @contract
    Scenario: An unknown task exits 2 and lists the tasks
      When "rdm-spec frobnicate" is run
      Then it exits 2, names frobnicate, and lists every task
