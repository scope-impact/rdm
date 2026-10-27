# Kernel component Plugins: dhf/spec/model.edn, :kernel.
#
# The core harness, and plugins each project composes (dhf/decisions/
# adr-0003-core-and-plugins.md, spec owner, spec-25). A project's own judge
# (rdm-spec's held-still oracle, Löyly's affordance contract and its
# conformance check, a domain model) was either built into the core or bolted
# onto a check; in the core it grows the core with every project's idea and
# cannot evolve with its project. A plugin is a command the project keeps in a
# kept path, declared in its configuration's :plugins, that either subscribes
# to an event the core publishes (its published language, events.edn) or
# answers a request the core makes (a Gate, a Check). Löyly keeps the
# configuration as everything a repository knows about itself
# (loyly:harness/README.md@17d68d2, the pieces table, config.sh).

@UN-015
Feature: Plugins
  Runs the project's plugins: each subscribes to an event the core publishes,
  or answers a request the core makes, through one interface.

  Rule: A plugin is declared in the configuration, in the order it runs

    @spec-0349 @contract
    Scenario: The plugins are the configuration's :plugins, in this shape
      Given a configuration whose :plugins is [{:name "lint-a" :subscribes [:SpecParsed] :command ["bb" "dhf/harness/plugins/lint-a/run.clj"] :seconds 20} {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}]
      When "rdm-spec check" is run
      Then each entry is read as exactly :name, :command, one of :subscribes (a vector of event names) or :answers (:Gate or :Check), an optional :seconds, its time limit, and an optional :env
      And a plugin with no :seconds has 60 seconds

    @spec-0350 @e2e
    Scenario Outline: A declaration rdm-spec cannot trust is refused before anything runs
      Given a configuration whose :plugins holds <entry>
      When "rdm-spec check" is run
      Then it exits 2, names the plugin, and says <why>

      Examples:
        | entry                                                                       | why                                                                       |
        | a plugin with neither :subscribes nor :answers                              | a plugin subscribes to events or answers a request                        |
        | a plugin with both :subscribes and :answers                                 | a plugin subscribes to events or answers a request, not both              |
        | a plugin subscribing to :NoSuchEvent                                        | rdm-spec publishes no event NoSuchEvent, and lists the events it publishes |
        | a plugin answering :Review                                                  | the requests are :Gate and :Check                                         |
        | two plugins named "lint-a"                                                  | a plugin's name is declared once                                          |
        | a plugin whose command is under the implementation's paths                  | a plugin is a judge the builder never writes                              |

  # The core's events are its published language: a plugin reads the event as
  # events.edn declares it, and nothing of the core's internals.
  Rule: A subscriber receives each event it subscribes to, in every task that publishes it

    @spec-0351 @contract
    Scenario: A subscriber is given the event on its standard input
      Given a plugin subscribing to :SpecParsed whose command writes what it is given to a file in the project
      When "rdm-spec check" is run
      Then the plugin ran once, in the project's root, with the environment variable RDM_SPEC set as the oracle's is
      And its standard input was the SpecParsed event as EDN: {:event :SpecParsed} merged with every key events.edn says the event carries

    @spec-0352 @e2e
    Scenario: Plugins run in the order declared, after the core's own stages
      Given two plugins subscribing to :SpecParsed, "lint-a" then "lint-b", each writing its name and the time to a shared file
      When "rdm-spec check" is run
      Then lint-a ran before lint-b, and both after the spec check's own stages

    @spec-0353 @e2e
    Scenario: A plugin subscribing to an event a task does not publish does not run in that task
      Given a plugin subscribing only to :VerificationDerived
      When "rdm-spec check" is run
      Then the plugin did not run

  # A plugin reports as a task does (dhf/spec/kernel/command.feature,
  # spec-0178): its findings one per line on its error stream, as
  # FILE:LINE: RULE: what; its standard output is relayed as notes.
  Rule: A plugin answers through one interface

    @spec-0354 @e2e
    Scenario Outline: What a plugin says becomes the task's own report
      Given a plugin subscribing to :SpecParsed that <does>
      When "rdm-spec check" is run on a spec that passes every check
      Then it <exits>, and the error stream <holds>

      Examples:
        | does                                                                          | exits      | holds                                                                     |
        | writes "dhf/spec/a/b.feature:4: action-unoffered: no form offers it" on its error stream and exits 1 | exits 1 | that line, unchanged, as a finding                  |
        | writes "dhf/spec/a/b.feature:4: warning: label-long: …" on its error stream and exits 0             | exits 0 | that line, unchanged, as a warning                  |
        | writes nothing and exits 0                                                                         | exits 0 | no line from the plugin                             |
        | exits 2, writing "cannot read affordances.yaml" on its error stream                                | exits 2 | cannot-run, naming the plugin and relaying its line |

    # Cycle 04 of Löyly: a probe stopped at its check's limit was reported as
    # "exited ," with nothing it printed, and took two cuts to diagnose.
    @spec-0355 @e2e
    Scenario: A plugin that runs past its time limit is stopped and named as having timed out
      Given a plugin subscribing to :SpecParsed with :seconds 2, which prints "started" and then sleeps for 10 seconds
      When "rdm-spec check" is run
      Then it exits 2 within 5 seconds, says the plugin timed out after 2 seconds, and relays "started"
      And no process of the plugin is still running

    @spec-0356 @e2e
    Scenario: A plugin whose command cannot be started cannot run
      Given a plugin whose command is ["no-such-plugin"]
      When "rdm-spec check" is run
      Then it exits 2 and names the plugin and its command

  # The core asks; the plugin answers. A Gate runs before fence and verify, in
  # place of the oracle that was built in (dhf/spec/measurement/oracle.feature).
  Rule: An answerer is asked at its request's place

    @spec-0357 @e2e
    Scenario: Gate plugins run before fence and verify, in the order declared, and stop them while one fails
      Given a project with two plugins answering :Gate, "first" exiting 0 and "second" exiting 1 with the finding "x:1: oracle-differs: …"
      When "rdm-spec fence room spec-3" is run
      Then first ran, then second; it exits 1, relays the finding unchanged, and builds no room

    @spec-0358 @contract
    Scenario: A Gate plugin is given the task it gates
      Given a plugin answering :Gate whose command writes what it is given to a file in the project
      When "rdm-spec verify ROOM 03" is run
      Then its standard input was {:request :Gate :task :verify :cut "spec-3" :cycle "03"}

  # Plugins are shared as templates, never as a running dependency: a project
  # copies one and then owns it (ADR 0003, "Shared as templates").
  Rule: A plugin is added from the templates rdm-spec carries, and then owned

    # Cycle 21: packaged from its sources alone, rdm-spec carried no template,
    # and plugin add found none (spec-0359, spec-0360, wording). Every package
    # carries them (spec owner, spec-26).
    @spec-0364 @e2e
    Scenario Outline: rdm-spec carries the same templates however it was installed
      Given rdm-spec installed <how>
      When "rdm-spec plugin add no-such" is run in a project set up by rdm-spec
      Then it lists the templates ddd-domain and affordances, each with its version

      Examples:
        | how                         |
        | from its sources            |
        | as a package (a jar)        |

    @spec-0359 @e2e
    Scenario: plugin add copies a template into the project and says how to declare it
      Given a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain
      When "rdm-spec plugin add ddd-domain" is run
      Then the template ddd-domain rdm-spec carries is copied into dhf/harness/plugins/ddd-domain, unchanged
      And dhf/harness/plugins/ddd-domain/origin.edn holds exactly {:template "ddd-domain" :version V :copied D}, V the template's version and D today's date
      And it prints the :plugins entry to add to the configuration, and changes the configuration not at all

    @spec-0360 @e2e
    Scenario Outline: plugin add never overwrites and never guesses
      Given a project set up by rdm-spec <given>
      When "rdm-spec plugin add <name>" is run
      Then it exits <exit> and <says>

      Examples:
        | given                                        | name       | exit | says                                                         |
        | that already has dhf/harness/plugins/ddd-domain | ddd-domain | 1    | names the directory and copies nothing                       |
        | and nothing else                             | no-such    | 2    | names no-such and lists the templates rdm-spec carries       |

  # spec-29, from halla-health's wallet: its oracle needed the e2e tests'
  # folder and the sandbox's send window, which lived in the integrator's
  # environment and the plugin's script, a difference nobody declared.
  Rule: What a plugin needs to know is in the configuration

    @spec-0382 @e2e
    Scenario: A plugin's :env is its environment, beside RDM_SPEC
      Given a plugin subscribing to :SpecParsed with :env {"ORACLE_DIR" "../e2e" "SEND_LIMIT" "5"} whose command writes its environment to a file in the project
      When "rdm-spec check" is run
      Then the plugin's environment held ORACLE_DIR "../e2e", SEND_LIMIT "5" and RDM_SPEC, and the rest of the caller's environment
      And an :env that is not a map of strings to strings is refused before anything runs, naming the plugin

  # spec-29: a project's grading (deploying its build for its graders, however
  # the project ships) starts when a cycle is verified, which no event said.
  Rule: A plugin can follow a verified cycle

    @spec-0383 @e2e
    Scenario: A subscriber to :CycleVerified runs after verify passes, given the cycle
      Given a plugin subscribing to :CycleVerified whose command writes what it is given to a file in the project
      When "rdm-spec verify ROOM 03" is run and passes
      Then after every check passed, the plugin ran once, in the project's root, with {:event :CycleVerified :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build" :checks [...]} on its input, the checks as ratified.edn holds them
      And its findings are verify's, and a plugin that cannot run makes verify exit 2 after ratified.edn is written

    @spec-0384 @e2e
    Scenario: A cycle that is not verified publishes nothing
      Given a plugin subscribing to :CycleVerified
      When "rdm-spec verify ROOM 03" is run and its tests check fails
      Then the plugin did not run

