# Component Trace, container verification: dhf/spec/model.edn.

@UN-003
Feature: Trace
  A need or a scenario, with everything linked to it, in both directions.

  Rule: A need traces to its scenarios

    @spec-0102 @e2e
    Scenario: Tracing a need lists every scenario that traces to it, however it is tagged
      Given UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third
      When UN-003 is traced
      Then the trace lists every scenario under that feature, every scenario under that rule, and that scenario
      And each with its id, title, component and level

    @spec-0103 @e2e
    Scenario: A need nothing traces to says so
      Given the declared need UN-007 and no scenario tracing to it
      When UN-007 is traced
      Then the trace says no scenario traces to UN-007

  Rule: A scenario traces to its need and its evidence

    @spec-0104 @e2e
    Scenario: Tracing a scenario names its needs, its component and its container
      Given the scenario @spec-0012 in the component Lint of the container architecture, under a feature tagged UN-005
      When spec-0012 is traced
      Then the trace names UN-005, the component Lint and the container architecture, and the scenario's level

    @spec-0105 @contract
    Scenario: With results, the trace gives each scenario's status and the results behind it
      Given an Allure results directory with two results labelled spec=spec-0012, one passed and one failed
      When spec-0012 is traced with that results directory
      Then the trace gives the status failed
      And it lists both results with their status and file

  Rule: The trace refuses what it cannot read

    @spec-0106 @e2e
    Scenario: An id that names nothing is refused
      Given no scenario tagged @spec-0999 and no need UN-999
      When spec-0999 is traced
      Then the trace refuses and names spec-0999

    @spec-0107 @e2e
    Scenario: A results directory that does not exist is refused
      When spec-0012 is traced with a results directory that does not exist
      Then the trace refuses and names the directory

    @spec-0108 @e2e
    Scenario: Without results, the trace says no status was derived
      When spec-0012 is traced with no results directory
      Then the trace lists the scenario's needs, component and level
      And it says no status was derived because no results were given
