@UN-007
Feature: Verify
  Decides whether a build is done, by checks the builder does not control and
  the project declares.

  Rule: Verify trusts only the cut

    @spec-0202 @e2e
    Scenario: Verify reads the cycle before anything else it does
      Given dhf/cycles/03/cycle.edn names the cut spec-3
      When "rdm-spec verify ROOM 03" is run
      Then it verifies against a fresh export of spec-3: its files, and no history

    @spec-0203 @e2e
    Scenario: A cycle with no cycle.edn is refused
      Given no dhf/cycles/03/cycle.edn
      When "rdm-spec verify ROOM 03" is run
      Then it exits 2 and names the missing file

    @spec-0204 @e2e
    Scenario: The checkout's implementation is replaced by the room's, never merged with it
      Given the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file
      When "rdm-spec verify ROOM 03" is run
      Then the checkout it verifies holds no src/rdm_spec/old.clj
      And every configured implementation path holds exactly what the room holds

    @spec-0205 @e2e
    Scenario: Edits the builder made outside the implementation are not used
      Given the builder has edited dhf/harness/config.edn and a feature file in the room
      When "rdm-spec verify ROOM 03" is run
      Then the configuration and the spec used are those of spec-3, unedited

  Rule: The tests check passes only when the tests pass and every scenario passed

    @spec-0206 @contract
    Scenario: The builder's tests are told where to write their results
      When "rdm-spec verify ROOM 03" runs the command of the :tests check
      Then the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in

    @spec-0207 @e2e
    Scenario: The tests check passes when the builder's tests pass and every scenario's results passed
      Given the :tests check's command exits 0
      And it wrote Allure results into dhf/cycles/03/results
      And every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed
      When "rdm-spec verify ROOM 03" is run
      Then the tests check passes

    @spec-0197 @e2e
    Scenario: A failing test command fails verification
      Given the :tests check's command exits 1
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1 and says the test command failed, with its exit status
      And the test command's output is in dhf/cycles/03/test.log, which the refusal names

    @spec-0033 @e2e
    Scenario: The tests check requires results
      Given the :tests check's command exits 0 without writing any Allure result
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1 and says a green exit with no results is not a run

    @spec-0208 @e2e
    Scenario: A scenario no test names fails verification
      Given the stamped scenario @spec-0012 is not tagged @wip
      And no result in dhf/cycles/03/results is labelled spec=spec-0012
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1 and names spec-0012

    @spec-0218 @e2e
    Scenario: A failed or broken result fails verification
      Given the :tests check's command exits 0
      And a result in dhf/cycles/03/results labelled spec=spec-0012 is failed
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1 and names spec-0012 and its status

  Rule: Verify records what it verified

    @spec-0215 @contract
    Scenario: Verify writes ratified.edn with the cut, the room's tree, the outcome and every check
      Given a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check
      When "rdm-spec verify ROOM 03" is run and passes
      Then dhf/cycles/03/ratified.edn holds exactly :cut "spec-3", :room-tree H in full, :outcome :passed, :checkout "dhf/cycles/03/build" and :checks, each check as its standard output reports it
      And dhf/cycles/03/build is the verified checkout, which the grader grades

    @spec-0216 @contract
    Scenario: A failed verification is recorded with its reason
      When "rdm-spec verify ROOM 03" is run and the :tests check's command exits 1
      Then dhf/cycles/03/ratified.edn holds :outcome :refused and the reason

    @spec-0217 @e2e
    Scenario: A cut holding an unstamped scenario is not verified
      Given the spec at cut spec-3 holds a scenario with no id
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1 and names the file and line of the unstamped scenario

  Rule: A project declares its checks, and verify runs every one the same way

    @spec-0314 @contract
    Scenario: Verify reports every declared check, in order, with its outcome and margin
      Given a configuration whose :verify declares a :tests check, then a :runs check named "answers"
      And both pass
      When "rdm-spec verify ROOM 03" is run
      Then it exits 0, and its standard output, read as EDN, is exactly {:verify {:cycle "03" :cut "spec-3" :outcome :passed :checks [{:name "tests" :kind :tests :outcome :passed :seconds S1 :scenarios N} {:name "answers" :kind :runs :outcome :passed :seconds S2 :budget B :margin M}]}}, S1 and S2 the seconds each took, N the stamped scenarios not tagged @wip that results name, B the check's budget in seconds and M the seconds of it left unused

    @spec-0315 @e2e
    Scenario: The first check that fails stops the gate, and the rest are named as not run
      Given a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run

    @spec-0316 @e2e
    Scenario Outline: A configuration verify cannot trust is refused before anything runs
      Given a configuration whose :verify <is>
      When "rdm-spec verify ROOM 03" is run
      Then it exits 2, says <why>, and runs no check

      Examples:
        | is                                                        | why                                           |
        | declares no :tests check                                  | a project is verified by its tests first      |
        | declares a check of the kind :renders                     | :renders is not a kind of check: the kinds are :tests, :serves, :runs and :plugin |
        | declares a :serves check whose probe is under src/         | the probe is in the implementation the builder writes |
        | is absent                                                 | the configuration declares no :verify         |
        | declares a :tests check with no command                   | the tests check names no command              |

    @spec-0317 @e2e
    Scenario: A check whose command is not found refuses the gate
      Given a configuration whose :runs check names a command that is not on PATH
      When "rdm-spec verify ROOM 03" is run
      Then it exits 2 and names the check and the command it could not find

  Rule: A :serves check proves the app comes up and is this application

    @spec-0318 @e2e
    Scenario: A served app that answers and passes its probe passes, with its margin
      Given a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0
      When "rdm-spec verify ROOM 03" is run
      Then the check passes with :answered-on-poll 2, :polls 20 and :margin 18
      And no process the check started is still running

    @spec-0366 @contract
    Scenario: A probe is given the app's url, ending in "/", and has its own time limit
      Given a :serves check whose app answers on port P, with :probe-seconds 20
      When "rdm-spec verify ROOM 03" is run
      Then its probe runs in the checkout with {url} filled in as "http://127.0.0.1:P/", and may run for 20 seconds after the app answered
      And a :serves check with no :probe-seconds gives its probe 30 seconds

    @spec-0367 @e2e
    Scenario: A probe that runs past its time limit is stopped and named as having timed out
      Given a :serves check whose probe, with :probe-seconds 2, prints "probing" and then sleeps for 10 seconds
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1 within 6 seconds, the check is :failed, and it says the probe timed out after 2 seconds and relays "probing"
      And no process the check started is still running

    @spec-0319 @e2e
    Scenario: An app that does not answer within its budget fails, naming the budget
      Given a :serves check with the budget of 10 seconds in 20 polls, whose app never answers
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1, the check is :failed, and it says no answer came within 10 seconds
      And no process the check started is still running

    @spec-0320 @e2e
    Scenario: A port already held refuses the gate rather than measure another process
      Given a :serves check on a port another process already holds
      When "rdm-spec verify ROOM 03" is run
      Then it exits 2, names the port, and starts no app of a :serves check

    @spec-0346 @e2e
    Scenario: A port held only by the closing connections of an app that has gone is free
      Given a :serves check whose app was started and stopped on its port a moment ago, so only closing connections remain
      When "rdm-spec verify ROOM 03" is run
      Then the check starts its app on that port and passes

    @spec-0321 @e2e
    Scenario: An app that answers but fails its probe is not this application
      Given a :serves check whose app answers and whose probe exits 1
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1, the check is :failed, and it relays the probe's output

    @spec-0341 @e2e
    Scenario: An app that accepts connections but never sends an HTTP response does not answer
      Given a :serves check with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1, the check is :failed, and it says no answer came within 2 seconds

  Rule: A :runs check proves a command answers as the project expects

    @spec-0322 @e2e
    Scenario Outline: A command that answers otherwise than expected fails the check
      Given a :runs check expecting <expected>
      And its command <does>
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1, the check is :failed, and it names <difference>

      Examples:
        | expected                          | does                               | difference                          |
        | the exit 0                         | exits 3                            | the exit 3 where 0 was expected     |
        | standard output starting "{:help"  | prints "usage" and exits 0         | the first line it printed           |
        | an answer within 10 seconds        | is still running after 10 seconds  | the budget of 10 seconds            |

  Rule: The tests check holds every result to the project's Allure mapping

    @spec-0331 @e2e
    Scenario Outline: A result that breaks the mapping fails the tests check, naming the scenario and the element
      Given a configuration whose :allure is rdm-spec's default mapping
      And the tests check's results for @spec-0003 hold <wrong>
      When "rdm-spec verify ROOM 03" is run
      Then it exits 1, the tests check is :failed, and it names spec-0003, <finding> and the result's file, as FILE:LINE: RULE: what (the result's file)

      Examples:
        | wrong                                                        | finding                         |
        | a second step reworded from the scenario's line              | steps-differ-from-scenario      |
        | one step fewer than the scenario's lines                     | steps-differ-from-scenario      |
        | the story "Something else", not the scenario's rule          | mapping-differs: story          |
        | the epic of another bounded context                          | mapping-differs: epic           |
        | no issue link to UN-001, the need the scenario traces to     | mapping-differs: links          |
        | no browser-trace attachment, where :evidence requires one for @e2e | evidence-missing: browser-trace |

    @spec-0347 @e2e
    Scenario: A planned scenario's result is held to its spec label only
      Given a configuration whose :allure requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip
      And the tests check's results hold that scenario's skipped result, labelled planned, with no attachment
      When "rdm-spec verify ROOM 03" is run
      Then the tests check passes, and names no finding about that scenario

    @spec-0332 @e2e
    Scenario Outline: A mapping verify cannot read is refused before anything runs
      Given a configuration whose :allure <is>
      When "rdm-spec verify ROOM 03" is run
      Then it exits 2, says <why>, and runs no check

      Examples:
        | is                                                  | why                                                   |
        | maps :epic to :aggregate                            | :aggregate is not an element of the spec              |
        | maps :colour to :context                            | :colour is not an element of an Allure result         |
        | maps no label "spec"                                | the label spec, from the scenario's id, is required   |

    @spec-0333 @contract
    Scenario: A configuration with no :allure is held to rdm-spec's default mapping
      Given a configuration that declares no :allure
      When "rdm-spec verify ROOM 03" is run
      Then each result is held to {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags} :links {:need {:type "issue" :url "N#{id}"}} :evidence {}}, N the configuration's :needs
      And N is dhf/spec/needs.edn for a configuration that names no :needs
