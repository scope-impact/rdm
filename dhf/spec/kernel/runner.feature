# Kernel component Gherkin runner: dhf/spec/model.edn, :kernel.
#
# Decided by the event storm's hotspot H10 (dhf/storm/2026-10-24-rdm.md): the
# runner is kernel code, so any project whose step definitions are written for
# it can use it. A project in another stack uses its own runner and only has
# to write the same Allure results.

@UN-003
Feature: Gherkin runner
  Runs the scenarios' steps against their step definitions and writes Allure results.

  # rdm-spec run is offered to any project whose step definitions are written
  # for it, so the way a step is defined is a public contract.
  Rule: Step definitions are written against one small API

    @spec-0168 @contract
    Scenario: A step is defined by a pattern and a function
      Given a step definition file in the project's configured step directory that defines a step by the pattern "the spec holds (\d+) features" and a function of the context and the captured text, through the runner's step API
      When "rdm-spec run" runs a scenario with the step "Given the spec holds 20 features"
      Then the function is called with the scenario's context and "20"

    @spec-0169 @contract
    Scenario: Each step's return is the next step's context, and each scenario starts empty
      Given two steps whose functions each add a key to the context they are given
      When "rdm-spec run" runs a scenario with both steps
      Then the second function receives the key the first added
      And the next scenario's first step receives an empty context

    @spec-0170 @contract
    Scenario: A step two definitions match makes the result broken, naming both
      Given two step definitions whose patterns both match "Given a spec"
      When "rdm-spec run" runs a scenario with that step
      Then its result is broken and names both definitions

  Rule: Every scenario run leaves one result, labelled with what it verifies

    @spec-0151 @contract
    Scenario: A scenario's result is labelled with its id, component and context
      Given the scenario @spec-0012 in the component Lint of the bounded context architecture
      When "rdm-spec run" is run
      Then it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture
      And a kernel scenario's result is labelled context=kernel
      And each step is a step of the result, with its own status

    @spec-0152 @contract
    Scenario: A scenario outline writes one result per example row, all with the outline's id
      Given a stamped scenario outline with three example rows
      When "rdm-spec run" is run
      Then it writes three Allure results, each labelled with the outline's id and naming its row

    @spec-0153 @e2e
    Scenario: A failing step fails the result and names the step
      Given a scenario whose second step's definition throws
      When "rdm-spec run" is run
      Then the result is failed and names the second step and the error
      And the steps after it are reported as not run

    @spec-0032 @e2e
    Scenario: A step with no definition makes the result broken
      Given a stamped scenario with a step no step definition matches
      When "rdm-spec run" is run
      Then the scenario's Allure result is broken
      And the result names the unmatched step

    @spec-0154 @e2e
    Scenario: A planned scenario is not run, and its result says it is planned
      Given a stamped scenario tagged @wip, with step definitions for its steps
      When "rdm-spec run" is run
      Then the scenario's steps are not run
      And its result is skipped and labelled planned

  # Incident: a test runner named its suites twice, once in imports and once in
  # a run list, so a forgotten suite was silently green (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
  Rule: The runner discovers, and refuses to find nothing

    @spec-0155 @e2e
    Scenario: The runner runs every feature file the spec holds, found by looking, never listed
      Given a spec holding twenty feature files
      When "rdm-spec run" is run
      Then it runs the scenarios of all twenty

    @spec-0156 @e2e
    Scenario: A run that finds no feature file is refused
      Given a spec directory holding no feature file
      When "rdm-spec run" is run
      Then it exits 2, says no scenario was found, and writes no result

  # rdm-spec reads Allure results and writes them; it never invents a format
  # beside Allure's. Each element a result carries is Allure's own, filled from
  # the Gherkin scenario, so an Allure report is one more view of the same run,
  # and a project in any stack can write the same results with its own
  # Allure-Gherkin adapter (spec owner, after the Löyly comparison,
  # dhf/cycles/loyly-comparison.md, finding 2: a builder could not learn the
  # result format from its own project).
  Rule: Every result is written in Allure's own model, from its Gherkin scenario

    @spec-0308 @contract
    Scenario Outline: A scenario's result carries each Allure element, filled from the scenario
      Given the stamped scenario @spec-0012 "Lint names the view", tagged @e2e and @UN-002, under the rule "Every view is described" of the feature "Lint" in dhf/spec/architecture/lint.feature, owned by the component Lint of the bounded context architecture
      When "rdm-spec run" is run and the scenario passes
      Then its Allure result holds <element> as <value>

      Examples:
        | element                  | value                                                                                 |
        | name                     | "Lint names the view"                                                                 |
        | fullName                 | "dhf/spec/architecture/lint.feature:Lint names the view"                              |
        | historyId                | "spec-0012", so its history follows the id however the title is reworded             |
        | status and stage         | "passed" and "finished"                                                               |
        | start and stop           | the scenario's start and stop, in milliseconds since the epoch                        |
        | steps                    | one step per Gherkin step, in order, each with its keyword and text as its name, its status, start and stop |
        | the label feature        | "Lint"                                                                                |
        | the label story          | "Every view is described"                                                             |
        | the label epic           | "architecture", the bounded context's id                                              |
        | the label suite          | "architecture", the bounded context's id                                              |
        | the label subSuite       | "architecture/lint", the component's id without its colon                             |
        | the labels tag           | one per Gherkin tag, without its @: "spec-0012", "e2e", "UN-002"                      |
        | the labels spec, context and component | "spec-0012", "architecture" and "Lint"                                   |
        | the label framework      | "gherkin"                                                                             |
        | links                    | one per need the scenario traces to: {"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"} |

    @spec-0309 @contract
    Scenario: An outline row's values are the result's parameters, and its history is the row's
      Given a stamped scenario outline @spec-0013 whose example row has the values plan and 3
      When "rdm-spec run" is run
      Then that row's result holds the parameters [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}], named by the table's headings
      And its historyId is "spec-0013" followed by the row's values, so each row has its own history

    @spec-0310 @contract
    Scenario: A result that did not pass says why, in Allure's statusDetails
      Given a scenario whose second step throws the error "no such view"
      When "rdm-spec run" is run
      Then its result's status is "failed" and its statusDetails hold the message "no such view" and the trace
      And its third step's status is "skipped"

    @spec-0311 @contract
    Scenario: Evidence a step attaches is an Allure attachment
      Given a step definition that attaches the text "GET / answered 200" as evidence named "response"
      When "rdm-spec run" is run
      Then the result's step holds an attachment named "response" of the type "text/plain"
      And its source is a file beside the result in the results directory, holding that text

    # Two of the six things cycle 02's builder of the Löyly app decided alone
    # (dhf/cycles/loyly-comparison.md, cycle 02).
    @spec-0323 @contract
    Scenario Outline: Failed is a Then that did not hold; broken is anything else that went wrong
      Given a scenario whose <step> <goes-wrong>
      When "rdm-spec run" is run
      Then its result's status is "<status>"

      Examples:
        | step        | goes-wrong                                  | status |
        | Then step   | finds what it asserts does not hold         | failed |
        | Given step  | throws an error                             | broken |
        | When step   | throws an error                             | broken |
        | Then step   | throws an error that is not an assertion    | broken |

    @spec-0324 @e2e
    Scenario: Results go to the configured results directory when RESULTS_DIR is not set
      Given a configuration whose :results-dir is dhf/allure-results, and RESULTS_DIR unset
      When "rdm-spec run" is run
      Then the results are written into dhf/allure-results
