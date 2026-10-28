@UN-003
Feature: Gherkin runner
  Runs the scenarios' steps against their step definitions and writes Allure results.

  Rule: A step definition file is a command that speaks EDN

    @spec-0168 @contract
    Scenario: A step file answers its patterns, and is invoked with the context and the captured texts
      Given an executable file S in the project's configured step directory that, run as "S definitions", writes ["the spec holds (\\d+) features"] on its standard output, and, run as "S invoke 0", writes its standard input to a file in the project and exits 0
      When "rdm-spec run" runs a scenario with the step "Given the spec holds 20 features"
      Then S was run as "S invoke 0", 0 the pattern's place in the vector it answered, counting from 0
      And its standard input, read as EDN, was {:context {} :captured ["20"]}

    @spec-0169 @contract
    Scenario: Each step's return is the next step's context, and each scenario starts empty
      Given two steps whose step files, invoked, each write {:return C} on their standard output, C the :context they were given with a key added, and exit 0
      When "rdm-spec run" runs a scenario with both steps
      Then the second step file's :context holds the key the first added
      And the next scenario's first step file is given the :context {}
      And a step that writes {:return R}, R anything but a map, leaves the context as it was

    @spec-0170 @contract
    Scenario: A step two definitions match makes the result broken, naming both
      Given two step definitions, in one step file or in two, whose patterns both match "Given a spec"
      When "rdm-spec run" runs a scenario with that step
      Then its result is broken and names both definitions, each by its step file and its pattern

    @spec-0457 @e2e
    Scenario: Every executable file under the step directory is a step file, asked for its definitions once per run
      Given a step directory holding the executable files a/given and b/then, each defining a pattern, and the file notes.md, not executable
      And two scenarios, each with a step of a's pattern and a step of b's
      When "rdm-spec run" is run
      Then a/given and b/then were each run as "FILE definitions" once, a/given first, in the order of their paths
      And each was run as "FILE invoke N" once for each step it matched, and notes.md was never run

    @spec-0458 @contract
    Scenario Outline: A pattern is a regular expression matched against the whole of a step's text, its keyword left out
      Given a step file whose only pattern, as it writes it in EDN, is <pattern>
      When "rdm-spec run" runs a scenario whose step is "<step>"
      Then <outcome>

      Examples:
        | pattern                            | step                                   | outcome                                                           |
        | "the spec holds (\\d+) features"   | Given the spec holds 20 features       | the step file is invoked with :captured ["20"]                    |
        | "the spec holds (\\d+) features"   | Given the spec holds 20 features twice | no definition matches the step: the whole text must match         |
        | "(\\w+) holds (\\d+) (?:features\|rules)" | And plan holds 3 rules          | the step file is invoked with :captured ["plan" "3"], one per group that captures, in order |
        | "a spec"                           | But a spec                             | the step file is invoked with :captured [], whatever the keyword |

    @spec-0459 @contract
    Scenario Outline: A step attaches evidence in its answer, text/plain unless it says otherwise
      Given a Then step whose file writes {:return {} :attachments [{:name "response" :content "{\"ok\" true}"<type>}]} on its standard output and exits <exit>
      When "rdm-spec run" is run
      Then the result's step is <status> and holds an attachment named "response" of the type "<kind>", its source a file beside the result holding {"ok" true}

      Examples:
        | type                       | exit | status | kind             |
        |                            | 0    | passed | text/plain       |
        |  :type "application/json"  | 0    | passed | application/json |
        |                            | 1    | failed | text/plain       |

    @spec-0460 @contract
    Scenario: A step's doc string and data table are given beside its captured texts
      Given a step "Given a spec holding:" followed by the doc string "Feature: x", and a step "Given these rows:" followed by a data table of two rows, "a" "b" and "1" "2"
      When "rdm-spec run" runs that scenario
      Then the first step file's standard input held :doc-string "Feature: x", and the second's :table [["a" "b"] ["1" "2"]], every row with its cells in order, the heading row first
      And a step with neither is given neither key

    @spec-0461 @e2e
    Scenario: A step file runs in the project's root, with RDM_SPEC set as a plugin's is
      Given a step file that writes its working directory and its environment to a file in the project
      When "rdm-spec run" is run
      Then it was run, asked for its definitions and invoked, in the project's root, with the tests' environment and RDM_SPEC set as the oracle's is

    @spec-0462 @e2e
    Scenario Outline: A run whose step files cannot answer is refused
      Given a project with one stamped scenario, whose configured step directory <holds>
      When "rdm-spec run" is run
      Then it exits 2, names <names>, prints nothing on standard output, and writes no result

      Examples:
        | holds                                                                     | names                        |
        | does not exist                                                            | the step directory           |
        | holds an executable file S that cannot be started                         | S                            |
        | holds an executable file S that, asked for its definitions, exits 1       | S and its exit               |
        | holds an executable file S that, asked for its definitions, writes "hello" | S, its answer not a vector of strings |

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
      Then it writes three Allure results, each labelled with the outline's id, sharing its name, and told apart by its parameters and historyId

    @spec-0153 @e2e
    Scenario: A failing step fails the result and names the step
      Given a scenario whose second step, a Then, finds what it asserts does not hold
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

    @spec-0463 @e2e
    Scenario: A run adds its results and removes nothing an earlier run left
      Given a results directory DIR holding a result and an attachment an earlier run wrote
      When "rdm-spec run --results DIR" is run
      Then DIR holds the earlier result and attachment, unchanged, beside one new result per scenario run

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

    @spec-0464 @e2e
    Scenario Outline: The runner runs every scenario of every spec, and labels only those with one id
      Given <project>
      When "rdm-spec run" is run
      Then <runs>

      Examples:
        | project                                                                                     | runs                                                                                  |
        | a configuration whose :specs declares auth and infra, each holding one stamped scenario      | it writes a result for each, each labelled spec with its own scenario's id            |
        | a configuration with :spec-layout :slices, and two slices each holding one stamped scenario | it writes a result for each                                                           |
        | a spec holding a scenario with no id                                                        | it runs it, and its result carries no spec label                                      |
        | a spec holding a scenario tagged with two ids                                               | it runs it, and its result carries no spec label                                      |

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
        | links                    | one per need the scenario traces to: {"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"}, the url's file the configuration's :needs |

    @spec-0309 @contract
    Scenario: An outline row's values are the result's parameters, and its history is the row's
      Given a stamped scenario outline @spec-0013 whose example row has the values plan and 3
      When "rdm-spec run" is run
      Then that row's result holds the parameters [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}], named by the table's headings
      And its historyId is "spec-0013|plan|3", the id and the row's values joined by |, so each row has its own history

    @spec-0465 @contract
    Scenario: A feature's Background runs before each of its scenarios, and its steps are the result's first steps
      Given a feature whose Background holds one step that adds a key to the context, and two stamped scenarios, one of them an outline with two rows
      When "rdm-spec run" is run
      Then the Background's step ran before each scenario and each row, given the :context {}, and each scenario's first step was given the key it added
      And each of the three results' steps begins with the Background's step, its keyword and text as its name, then the scenario's own

    @spec-0466 @contract
    Scenario: The runner fills each element as the project's Allure mapping says
      Given a configuration whose :allure is rdm-spec's default mapping with :epic mapped to :feature, and the stamped scenario @spec-0012 under the feature "Lint" of the bounded context architecture
      When "rdm-spec run" is run
      Then its result's label epic is "Lint", and every other element is as the default mapping fills it
      And verify's tests check, holding the result to that mapping, finds nothing about it

    @spec-0310 @contract
    Scenario: A result that did not pass says why, in Allure's statusDetails
      Given a scenario whose second step, a Then, writes the two lines "no such view" and "at line 3" on its error stream and exits 1, its assertion not holding
      When "rdm-spec run" is run
      Then its result's status is "failed", its statusDetails message is "no such view", the first line of the step's error stream, and its statusDetails trace is the whole error stream
      And its third step's status is "skipped"

    @spec-0311 @contract
    Scenario: Evidence a step attaches is an Allure attachment
      Given a step definition that attaches the text "GET / answered 200" as evidence named "response"
      When "rdm-spec run" is run
      Then the result's step holds an attachment named "response" of the type "text/plain"
      And its source is a file beside the result in the results directory, holding that text

    @spec-0323 @contract
    Scenario Outline: Failed is a Then that did not hold; broken is anything else that went wrong
      Given a scenario of four steps, a Given, a When, a Then and an And, whose <step> step's file <goes-wrong>
      When "rdm-spec run" is run
      Then that step's status, and the result's, is "<status>"

      Examples:
        | step  | goes-wrong                                       | status |
        | Then  | exits 1: what it asserts does not hold           | failed |
        | And   | exits 1, an And after a Then being a Then        | failed |
        | Given | exits 1                                          | broken |
        | When  | exits 1                                          | broken |
        | Then  | exits 2                                          | broken |
        | Then  | exits 3                                          | broken |
        | Then  | is ended by the signal KILL                      | broken |

    @spec-0324 @e2e
    Scenario: Results go to the configured results directory when RESULTS_DIR is not set
      Given a configuration whose :results-dir is dhf/allure-results, no such directory, and RESULTS_DIR unset
      When "rdm-spec run" is run
      Then it creates dhf/allure-results and writes the results into it

  Rule: A scenario's tags are its own and those it inherits

    @spec-0403 @contract
    Scenario: A scenario's tag labels are its feature's, its rule's and its own, in that order
      Given a feature tagged @UN-001, a rule under it tagged @UN-002, and under the rule the stamped scenario @spec-0003 tagged @e2e
      When "rdm-spec run" is run and the scenario passes
      Then its result's tag labels are "UN-001", "UN-002", "spec-0003" and "e2e", in that order, each once
      And verify's tests check holds a result to exactly these tags, under the default mapping's "tag" :gherkin-tags

  Rule: Run's result has one shape

    @spec-0467 @contract
    Scenario Outline: Run reports its results, and fails when any result did not pass
      Given a project whose stamped scenarios are @spec-0001, at line 4 of dhf/spec/a/b.feature, and @spec-0002 tagged @wip, and whose step files make @spec-0001 <goes>
      When "rdm-spec run --results DIR" is run
      Then it exits <exit>, and its standard output, read as EDN, is exactly {:run {:results DIR :scenarios 2 :counts {:passed <p> :failed <f> :broken <b> :skipped 1}}}, :scenarios the scenarios it wrote results for, planned ones included, an outline counted once, and :counts the results by status
      And its error stream holds <line>

      Examples:
        | goes  | exit | p | f | b | line                                                                         |
        | pass  | 0    | 1 | 0 | 0 | no line                                                                      |
        | fail  | 1    | 0 | 1 | 0 | one finding, dhf/spec/a/b.feature:4: RULE: what, naming spec-0001 and failed |
        | break | 1    | 0 | 0 | 1 | one finding, dhf/spec/a/b.feature:4: RULE: what, naming spec-0001 and broken |
