# Component Trace, bounded context verification of the rdm-spec container: dhf/spec/model.edn.

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

  # Cycle 08: spec/check's step read trace's result in one shape and the
  # rebuilt trace wrote another; the shape is now the spec's.
  Rule: The trace's result has one shape

    @spec-0279 @contract
    Scenario: A need's trace is one map under :trace
      Given UN-003 tagged on one scenario, and a results directory with a passing result for it
      When "rdm-spec trace UN-003 --results DIR" is run
      Then standard output is {:trace {:kind :need :id "UN-003" :text ... :scenarios [...]}}
      And each of its scenarios is a map with :id, :title, :component, :context, :level and :status

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

    @spec-0108 @e2e
    Scenario: Without results, the trace says no status was derived
      Given a copy of rdm-spec's own project in which the configured results directory does not exist
      When "rdm-spec trace spec-0012" is run with no --results
      Then the trace lists the scenario's needs, component and level
      And it says no status was derived because no results were given
