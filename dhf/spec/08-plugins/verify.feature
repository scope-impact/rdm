@UN-007 @UN-015
Feature: Verify
  Asks a project's own judge as one of its checks.

  Rule: A :plugin check asks the project's own judge

    @spec-0361 @e2e
    Scenario Outline: A :plugin check passes, fails or cannot run as its plugin answers
      Given a configuration whose :verify declares a :tests check, then {:kind :plugin :name "judge"}, and whose :plugins has "judge" answering :Check
      And the tests check passes, and the plugin <answers>
      When "rdm-spec verify ROOM 03" is run
      Then it <exits>, and the check "judge" is <outcome>

      Examples:
        | answers                                                      | exits                                         | outcome   |
        | exits 0                                                      | exits 0                                       | :passed   |
        | writes "src/app.clj:9: form-missing: …" on its error stream and exits 1 | exits 1, relaying the finding unchanged | :failed   |
        | exits 2                                                      | exits 2, naming the plugin                    | :refused  |

    @spec-0391 @e2e
    Scenario: A Check plugin's results are held to the mapping, and count
      Given a :plugin check "acceptance" whose plugin writes Allure results of every scenario into the :results directory its request names
      When "rdm-spec verify ROOM 03" is run
      Then its request held :results, the absolute path of dhf/cycles/03/results/acceptance, and each result there was held to the :allure mapping as the tests check's are
      And a result that did not pass, or a scenario with no result, fails the check "acceptance", naming the scenario
      And a plugin that writes no results is judged by its exit alone, as before

    @spec-0362 @contract
    Scenario: A Check plugin is asked in the checkout, with its cycle and cut
      Given a :plugin check whose plugin writes what it is given to a file
      When "rdm-spec verify ROOM 03" is run
      Then the plugin ran in the checkout, dhf/cycles/03/build, and its standard input was {:request :Check :cycle "03" :cut "spec-3" :checkout C :results R}, C the absolute path of dhf/cycles/03/build and R the absolute path of dhf/cycles/03/results/judge, in the repository rdm-spec verify was run in, so the plugin resolves both from where it runs

    @spec-0363 @e2e
    Scenario: A :plugin check naming no plugin answering :Check is refused before anything runs
      Given a configuration whose :verify declares {:kind :plugin :name "judge"}, and whose :plugins has no "judge" answering :Check
      When "rdm-spec verify ROOM 03" is run
      Then it exits 2, says the check names no plugin answering :Check, and runs no check
