@UN-003
Feature: Trace
  A need or a scenario, with everything linked to it, in both directions.

  Rule: A need traces to its scenarios

    @spec-0102 @e2e
    Scenario: Tracing a need lists every scenario that traces to it, however it is tagged
      Given UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third
      When "rdm-spec trace UN-003" is run
      Then the trace lists every scenario under that feature, every scenario under that rule, and that scenario
      And each with its id, title, component and level

    @spec-0103 @e2e
    Scenario: A need nothing traces to says so
      Given the declared need UN-007 and no scenario tracing to it
      When "rdm-spec trace UN-007" is run
      Then the trace says no scenario traces to UN-007

  Rule: A scenario traces to its need and its evidence

    @spec-0104 @e2e
    Scenario: Tracing a scenario names its needs, its component and its bounded context
      Given the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005
      When "rdm-spec trace spec-0012" is run
      Then the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level

    @spec-0105 @contract
    Scenario: With results, the trace gives each scenario's status and the results behind it
      Given an Allure results directory with two results labelled spec=spec-0012, one passed and one failed
      When "rdm-spec trace spec-0012 --results DIR" is run with that results directory as DIR
      Then the trace gives the status failed
      And it lists both results with their status and file

  Rule: The trace's result has one shape

    @spec-0279 @contract
    Scenario: A need's trace is one map under :trace
      Given UN-003 tagged on one scenario, and a results directory with a passing result for it
      When "rdm-spec trace UN-003 --results DIR" is run
      Then standard output is {:trace {:kind :need :id "UN-003" :text ... :scenarios [...]}}
      And each of its scenarios is a map with :id, :title, :component, :context, :level and :status

    @spec-0472 @contract
    Scenario: A scenario's trace is one map under :trace
      Given the stamped scenario @spec-0012 "Lint names the view", tagged @e2e, at line 9 of dhf/spec/04-a/lint.feature, under a feature tagged @UN-005, owned by the component Lint of the bounded context architecture
      And a results directory DIR holding a passing result F for it
      When "rdm-spec trace spec-0012 --results DIR" is run
      Then it exits 0, and its standard output, read as EDN, is exactly {:trace {:kind :scenario :id "spec-0012" :title "Lint names the view" :file "dhf/spec/04-a/lint.feature" :line 9 :needs ["UN-005"] :component "Lint" :context "architecture" :level :e2e :status :verified :results [{:file F :status "passed"}]}}, F as DIR/name
      And under several specs it holds :spec too, the name of the scenario's spec

    @spec-0473 @contract
    Scenario: A trace names a component by its name, a context by its id, and a level as a keyword
      Given UN-003 tagged on the stamped scenario @spec-0012, tagged @contract, owned by the component Lint, :architecture/lint in the model, of the bounded context :architecture named "Architecture"
      When "rdm-spec trace UN-003 --results DIR" is run, DIR holding a passing result for it
      Then its scenario's map is {:id "spec-0012" :title T :component "Lint" :context "architecture" :level :contract :status :verified}, T its title

    @spec-0474 @contract
    Scenario: A need nothing traces to has no scenarios, and says so in the trace
      Given the declared need UN-007, whose text is X, and no scenario tracing to it
      When "rdm-spec trace UN-007 --results DIR" is run, DIR a results directory with a result in it
      Then it exits 0, its error stream is empty, and its standard output, read as EDN, is exactly {:trace {:kind :need :id "UN-007" :text X :scenarios [] :says "no scenario traces to UN-007"}}

  Rule: The trace refuses what it cannot read

    @spec-0106 @e2e
    Scenario: An id that names nothing is refused
      Given no scenario tagged @spec-0999 and no need UN-999
      When "rdm-spec trace spec-0999" is run
      Then it exits 2 and names spec-0999

    @spec-0107 @e2e
    Scenario: A results directory that does not exist is refused
      When "rdm-spec trace spec-0012 --results DIR" is run with a DIR that does not exist
      Then it exits 2 and names the directory

    @spec-0475 @e2e
    Scenario: A --spec that names no spec is refused
      Given a configuration whose :specs declares auth and infra
      When "rdm-spec trace auth-0001 --spec nope" is run
      Then it exits 2, names nope, and the specs auth and infra, and prints nothing on standard output

    @spec-0476 @e2e
    Scenario: A results file that cannot be read is a warning in a trace
      Given a results directory DIR holding one result file F, as DIR/name, that is not valid JSON, beside a passing result for spec-0012
      When "rdm-spec trace spec-0012 --results DIR" is run
      Then it exits 0, its status is :verified, and its error stream names F as unreadable in one warning line, F: warning: RULE: what

    @spec-0108 @e2e
    Scenario: Without results, the trace says no status was derived
      Given a copy of rdm-spec's own project in which the configured results directory does not exist
      When "rdm-spec trace spec-0012" is run with no --results
      Then the trace lists the scenario's needs, component and level
      And it says no status was derived because no results were given

    @spec-0477 @contract
    Scenario Outline: With no results read, the trace holds no status and says why
      Given a project whose configured results directory <exists>, and RESULTS_DIR unset
      When "rdm-spec trace <id>" is run with no --results
      Then it exits 0, and its :trace <holds>

      Examples:
        | exists                                             | id        | holds                                                                                                                  |
        | does not exist                                     | spec-0012 | has no :status and no :results, and holds :status-note "no status was derived because no results were given"         |
        | does not exist                                     | UN-003    | holds :status-note "no status was derived because no results were given", and no map of its :scenarios has :status  |
        | exists, holding a passing result for spec-0012     | spec-0012 | holds :status :verified and the result, read from the configured results directory, and no :status-note              |
