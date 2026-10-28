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

    @spec-0441 @contract
    Scenario Outline: Every task that runs a plugin refuses a declaration it cannot trust first, and says nothing else
      Given a configuration whose :plugins holds a plugin with neither :subscribes nor :answers, named "x"
      And a spec in which a scenario carries no id
      When "rdm-spec <task>" is run
      Then it exits 2, and its error stream is one line, cannot-run, naming x
      And its standard output is empty, and it ran no plugin and no stage

      Examples:
        | task              |
        | check             |
        | oracle            |
        | status            |
        | fence ROOM spec-3 |
        | verify ROOM 03    |

    @spec-0442 @e2e
    Scenario: plugin add, which runs no plugin, is not refused by a declaration it cannot trust
      Given a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain, whose :plugins holds a plugin with neither :subscribes nor :answers
      When "rdm-spec plugin add ddd-domain" is run
      Then it exits 0 and copies the template

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

    @spec-0443 @e2e
    Scenario: A plugin is given every event it subscribes to, a run for each, before the next plugin runs
      Given two plugins, "lint-a" then "lint-b", each subscribing to [:SpecParsed :KnowledgeIndexed] and writing its name and the event it is given to a shared file
      When "rdm-spec check" is run
      Then the file holds, in this order: lint-a KnowledgeIndexed, lint-a SpecParsed, lint-b KnowledgeIndexed, lint-b SpecParsed
      And each plugin is given its events in the order the task published them, whatever order its :subscribes names them in

    @spec-0353 @e2e
    Scenario: A plugin subscribing to an event a task does not publish does not run in that task
      Given a plugin subscribing only to :VerificationDerived
      When "rdm-spec check" is run
      Then the plugin did not run

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

    @spec-0444 @e2e
    Scenario Outline: A plugin that cannot run stops no other plugin, and the others' findings are still written
      Given two plugins subscribing to :SpecParsed, "<first>" then "<second>": "broken" exits 2, writing "cannot read x" on its error stream; "lint" writes "a.txt:1: r: bad" on its error stream and exits 1
      When "rdm-spec check" is run on a spec that passes every check
      Then both plugins ran, in the order declared
      And it exits 2, its error stream holds "a.txt:1: r: bad" unchanged and a cannot-run line naming broken, and its standard output is empty

      Examples:
        | first  | second |
        | broken | lint   |
        | lint   | broken |

    @spec-0445 @e2e
    Scenario: A plugin's standard output is not the task's report
      Given a plugin subscribing to :SpecParsed that writes "a note" on its standard output and exits 0
      When "rdm-spec check" is run on a spec that passes every check
      Then it exits 0, its standard output is check's result alone, and neither stream holds "a note"

    @spec-0446 @contract
    Scenario Outline: A plugin's line of no finding shape, or a failure naming no finding, is a finding about the plugin
      Given a plugin "lint-a" subscribing to :SpecParsed that <does>
      When "rdm-spec check" is run on a spec that passes every check
      Then it exits 1, and its error stream holds the finding "dhf/harness/config.edn: <rule>: …", naming lint-a and <saying>

      Examples:
        | does                                                           | rule            | saying                  |
        | writes "this is not a finding" on its error stream and exits 0 | plugin-line     | holding the line        |
        | writes "this is not a finding" on its error stream and exits 1 | plugin-line     | holding the line        |
        | writes nothing on its error stream and exits 1                 | plugin-findings | that it named no finding |

    @spec-0447 @e2e
    Scenario Outline: A plugin that exits otherwise than 0, 1 or 2 cannot run
      Given a plugin "lint-a" subscribing to :SpecParsed that <ends>
      When "rdm-spec check" is run on a spec that passes every check
      Then it exits 2, and its error stream holds a cannot-run line naming lint-a and <names>

      Examples:
        | ends                                  | names                          |
        | exits 3                               | its exit, 3                    |
        | is ended by the signal KILL           | that a signal ended it         |

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

    @spec-0448 @contract
    Scenario: A Gate plugin is given the fence it gates
      Given a plugin answering :Gate whose command writes what it is given to a file in the project
      When "rdm-spec fence ROOM spec-3" is run
      Then its standard input was {:request :Gate :task :fence :cut "spec-3"}

    @spec-0449 @e2e
    Scenario: The Gate plugins after a failing one are not asked
      Given a project with three plugins answering :Gate, "first" exiting 0, "second" exiting 1 with the finding "x:1: oracle-differs: …", and "third" writing that it ran to a file in the project
      When "rdm-spec fence ROOM spec-3" is run
      Then it exits 1, relays second's finding unchanged, and third did not run

  Rule: A plugin is added from the templates rdm-spec carries, and then owned

    @spec-0405 @e2e
    Scenario: An added template's plugin runs as it is declared
      Given a project made by "rdm-spec init"
      When "rdm-spec plugin add ddd-domain" is run, and its entry is added to :plugins as it says
      Then every file its command names is in dhf/harness/plugins/ddd-domain/
      And "rdm-spec check" runs the plugin, and the plugin does not answer cannot run

    @spec-0364 @e2e
    Scenario Outline: rdm-spec carries the same templates however it was installed
      Given rdm-spec installed <how>
      When "rdm-spec plugin add no-such" is run in a project set up by rdm-spec
      Then it lists the templates ddd-domain and affordances, each with its version

      Examples:
        | how                         |
        | from its sources            |
        | as a package (its command copied outside its sources) |

    @spec-0359 @e2e
    Scenario: plugin add copies a template into the project and says how to declare it
      Given a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain
      When "rdm-spec plugin add ddd-domain" is run
      Then the template ddd-domain rdm-spec carries is copied into dhf/harness/plugins/ddd-domain, unchanged
      And dhf/harness/plugins/ddd-domain/origin.edn holds exactly {:template "ddd-domain" :version V :copied D}, V the template's version and D today's date
      And it prints the :plugins entry to add to the configuration, and changes the configuration not at all

    @spec-0450 @contract
    Scenario: plugin add's result has one shape
      Given a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain
      When "rdm-spec plugin add ddd-domain" is run
      Then its standard output, read as EDN, is {:plugin {:added "ddd-domain" :to "dhf/harness/plugins/ddd-domain" :version V :declare E :says S}}
      And V is the :version of the template's template.edn, E its :plugin entry exactly, the entry to add to :plugins, and S a string saying to add it

    @spec-0451 @contract
    Scenario: origin.edn records the template's version as written and the local date of the copy
      Given a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain, and TZ set to a time zone whose date, when it runs, is not UTC's
      When "rdm-spec plugin add ddd-domain" is run
      Then origin.edn's :version is the template's :version as its template.edn writes it, of the same type
      And its :copied is that time zone's date as a string YYYY-MM-DD, as "2026-10-29"

    @spec-0452 @e2e
    Scenario: A file the template carries as executable is copied executable
      Given a project set up by rdm-spec with no dhf/harness/plugins/affordances
      When "rdm-spec plugin add affordances" is run
      Then each file of the copy has the contents and the executable bit of the template's file, probe.sh executable among them

    @spec-0360 @e2e
    Scenario Outline: plugin add never overwrites and never guesses
      Given a project set up by rdm-spec <given>
      When "rdm-spec plugin add <name>" is run
      Then it exits <exit> and <says>

      Examples:
        | given                                        | name       | exit | says                                                         |
        | that already has dhf/harness/plugins/ddd-domain | ddd-domain | 1    | names the directory and copies nothing                       |
        | and nothing else                             | no-such    | 2    | names no-such and lists the templates rdm-spec carries       |

  Rule: What a plugin needs to know is in the configuration

    @spec-0382 @e2e
    Scenario: A plugin's :env is its environment, beside RDM_SPEC
      Given a plugin subscribing to :SpecParsed with :env {"ORACLE_DIR" "../e2e" "SEND_LIMIT" "5"} whose command writes its environment to a file in the project
      When "rdm-spec check" is run
      Then the plugin's environment held ORACLE_DIR "../e2e", SEND_LIMIT "5" and RDM_SPEC, and the rest of the caller's environment
      And an :env that is not a map of strings to strings is refused before anything runs, naming the plugin

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
