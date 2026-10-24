# Kernel component Command: dhf/spec/model.edn, :kernel.
#
# The one entry point a hook, CI, an agent and a person all use: the rdm-spec
# command, installed once, with nothing per project. It belongs to no bounded
# context: it dispatches each task to the context that owns it.
# Specs are written by editing files; the command only checks, stamps, draws,
# cuts, fences, runs, ratifies, derives checklists, files scores and publishes.
# Grading is not a task: a grader grades, however they grade.

@UN-009
Feature: Command
  The one command, rdm-spec, and its tasks.

  Rule: Everything runs through one command

    @spec-0176 @contract
    Scenario Outline: Each task is run as rdm-spec <task>
      Given a project set up by rdm-spec
      When "rdm-spec help" is run
      Then it lists the task <task> with its usage <usage>, described as <does>

      Examples:
        | task       | usage                                                    | does                                                                 |
        | help       | help                                                     | list every task with its usage                                       |
        | init       | init PATH [--id-prefix P]                                | write the skeleton into a path that does not exist                   |
        | adopt      | adopt                                                    | add the skeleton to the current repository, overwriting nothing      |
        | stamp      | stamp                                                    | give every unstamped scenario its id                                 |
        | check      | check [--sources DIR] [--events]                         | the spec check, the model lint, the views' freshness, sources, wiki  |
        | views      | views                                                    | generate and render every view to dhf/views/                         |
        | cut        | cut                                                      | tag HEAD spec-N when it is origin/HEAD, and push the tag             |
        | fence      | fence ROOM CUT [--context C \| --component C/N]          | build a clean room from a cut                                        |
        | run        | run [--results DIR]                                      | run the scenarios against a project's step definitions               |
        | ratify     | ratify ROOM CYCLE                                        | run the builder's tests on the room's code in a checkout of the cut  |
        | checklist  | checklist CYCLE                                          | derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json    |
        | status     | status [--results DIR]                                   | each scenario's status                                               |
        | trace      | trace ID [--results DIR]                                 | a need or a scenario with everything linked to it                    |
        | release    | release [--results DIR]                                  | the release check                                                    |
        | score      | score CYCLE --verdicts FILE                              | file a grader's verdicts on a ratified cycle into its score.edn      |
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
