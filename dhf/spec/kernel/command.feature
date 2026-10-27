# Kernel component Command: dhf/spec/model.edn, :kernel.
#
# The one entry point a hook, CI, an agent and a person all use: the rdm-spec
# command, installed once, with nothing per project. It belongs to no bounded
# context: it dispatches each task to the context that owns it.
# Specs are written by editing files; the command only checks, stamps, draws,
# cuts, fences, runs, verifies, derives checklists, files scores and publishes.
# Grading is not a task: a grader grades, however they grade.

@UN-009
Feature: Command
  The one command, rdm-spec: its tasks, their usage and how every task reports.

  Rule: Everything runs through one command

    @spec-0176 @contract
    Scenario Outline: Each task is run as rdm-spec <task>
      Given a project set up by rdm-spec
      When "rdm-spec help" is run
      Then its standard output, read as EDN, is {:help [{:task T :usage U :does D} ...]}, one map per task in the order below, T the task's name as a string
      And it lists the task <task> with its usage <usage>, described as <does>

      Examples:
        | task       | usage                                                    | does                                                                 |
        | help       | help                                                     | list every task with its usage                                       |
        | init       | init PATH [--id-prefix P]                                | write the skeleton into a path that does not exist                   |
        | adopt      | adopt                                                    | add the skeleton to the current repository, overwriting nothing      |
        | stamp      | stamp                                                    | give every unstamped scenario its id                                 |
        | check      | check [--sources DIR] [--events]                         | the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths, and the plugins that subscribe to what it reads |
        | views      | views                                                    | generate and render every view to dhf/views/                         |
        | cut        | cut                                                      | tag HEAD spec-N when it is origin/HEAD, and push the tag             |
        | scopes     | scopes CUT CUT                                           | list the scopes a spec change touched between two cuts               |
        | fence      | fence ROOM CUT [--context C,...] [--component C/N,...] [--cycle CYCLE] | build a clean room from a cut                          |
        | run        | run [--results DIR]                                      | run the scenarios against a project's step definitions               |
        | verify     | verify ROOM CYCLE                                        | run the project's checks on the room's code in a checkout of the cut |
        | checklist  | checklist CYCLE                                          | derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json    |
        | status     | status [--results DIR]                                   | each scenario's status                                               |
        | trace      | trace ID [--results DIR]                                 | a need or a scenario with everything linked to it                    |
        | release    | release [--results DIR]                                  | the release check                                                    |
        | grade      | grade CYCLE DIR                                          | build the grading rooms of a verified cycle in DIR, the build packaged outside them |
        | score      | score CYCLE --verdicts FILE [--verdicts FILE ...]        | file the graders' verdicts on a verified cycle into its score.edn    |
        | scoreboard | scoreboard CYCLE CYCLE                                   | compare two cycles                                                   |
        | oracle     | oracle [ARGS]                                            | run the oracle the project keeps: its plugin named "oracle"          |
        | plugin     | plugin add NAME                                          | copy a plugin template rdm-spec carries into dhf/harness/plugins/NAME |
        | publish    | publish [--results DIR]                                  | write the site and build it                                          |
        | audit      | audit OUT [--results DIR]                                | write the auditor's and the engineer's copy, with their evidence, to OUT |
        | observe    | observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE | record a run: its time, its cost and what it touched outside its room |

    @spec-0193 @contract
    Scenario: A task's results directory defaults to the configured one
      Given the configured results directory is dhf/allure-results
      When "rdm-spec status" is run with no --results
      Then it reads dhf/allure-results

    @spec-0219 @contract
    Scenario Outline: A task's results directory is --results, else RESULTS_DIR, else the configured one
      Given the configured results directory is dhf/allure-results
      And the environment variable RESULTS_DIR is <env>
      When "rdm-spec status <flag>" is run
      Then it reads <dir>

      Examples:
        | env     | flag            | dir              |
        | unset   |                 | dhf/allure-results |
        | R1      |                 | R1               |
        | R1      | --results other | other            |

  # A task runs its stages in order and every stage reports, so one run names
  # every finding. A gate is the exception: a stage the task table marks as a
  # gate stops the task when it has findings, and the stages after it never
  # run. The plugins answering :Gate are the gate before fence and verify, the
  # oracle among them (dhf/spec/kernel/plugins.feature; dhf/spec/measurement/
  # oracle.feature, spec-0232). A gate replaces an event the gated stages would
  # each have had to interpret (cycle 04).
  # Cycles 01 and 02 of halla-health's wallet (spec-27): the wallet is one
  # folder of a monorepo, and fence and verify read a cut by paths from the
  # repository's root, so they found no configuration at the cut; the cycles
  # ran on a copy of the folder as a repository of its own.
  @UN-006
  Rule: A project may be one folder of a larger repository

    @spec-0368 @e2e
    Scenario Outline: A task that reads a cut reads the project's folder at the cut
      Given a repository whose project is its folder wallet/, holding wallet/dhf/harness/config.edn, whose commits tagged spec-2 and spec-3 change one component's scenarios, and whose dhf/cycles/03/cycle.edn names the cut spec-3
      And files outside wallet/ at the same paths a project at the root would use: dhf/harness/config.edn and dhf/spec/model.edn
      When "rdm-spec <task>" is run in wallet/
      Then it exits as it would for the same project at a repository's root, and <reads>
      And it reads nothing outside wallet/

      Examples:
        | task                   | reads                                                                                |
        | fence ROOM spec-3      | ROOM holds wallet/'s committed files at its own root, minus the denied ones           |
        | verify ROOM 03         | its checkout is wallet/ exported at spec-3, at the checkout's root                    |
        | checklist 03           | the checklist holds one item per stamped scenario of wallet/'s spec at spec-3         |
        | scopes spec-2 spec-3   | it names the one component whose scenarios changed in wallet/                         |

    @spec-0369 @e2e
    Scenario: A cut is read as committed, however the project's working tree differs
      Given a project in the folder wallet/ whose working tree has an uncommitted change to a feature file and an untracked file, and whose HEAD is not the commit tagged spec-3
      When "rdm-spec fence ROOM spec-3" is run in wallet/
      Then ROOM holds the feature file as committed at spec-3, and neither the change nor the untracked file

  Rule: A gate stops the task

    @spec-0235 @contract
    Scenario: A task with no gate reports the findings of every stage
      Given a project whose spec has a scenario with no level and a view older than the model
      When "rdm-spec check" is run
      Then it exits 1 and names both the missing level and the stale view

  Rule: Every task reports the same way

    @spec-0177 @contract
    Scenario: A task that passes exits 0
      Given a spec that passes every check
      When "rdm-spec check" is run
      Then it exits 0

    @spec-0178 @contract
    Scenario: A task that finds a problem exits 1 and names it on the error stream
      Given a feature file in which one scenario carries no level tag
      When "rdm-spec check" is run
      Then it exits 1
      And the error stream holds one line per finding: "FILE:LINE: RULE: what is wrong", FILE and LINE left out where none applies
      And the standard output holds only the task's result, as EDN

    @spec-0179 @contract
    Scenario: A task that cannot run exits 2 and says why
      Given a copy of rdm-spec's own project in which the configured results directory does not exist
      When "rdm-spec status" is run
      Then it exits 2 and names the missing directory

    @spec-0180 @contract
    Scenario: An unknown task exits 2 and lists the tasks
      When "rdm-spec frobnicate" is run
      Then it exits 2, names frobnicate, and lists every task
