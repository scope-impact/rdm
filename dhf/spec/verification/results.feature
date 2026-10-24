# Component Results reader, bounded context verification of the rdm-spec container: dhf/spec/model.edn.

@UN-003
Feature: Results reader
  Reads a run's Allure results, whatever stack wrote them, naming what it cannot read.

  # Incident: a test runner named its suites twice, once in imports and once in
  # a run list, so a forgotten suite was silently green
  # (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
  Rule: Allure results are the oracle, whoever wrote them

    @spec-0060 @contract
    Scenario: A result naming no scenario is reported, not counted
      Given an Allure result labelled spec=spec-9999
      And no scenario in the spec is tagged @spec-9999
      When the results are read
      Then the report lists spec-9999 as an unknown result
      And it counts toward no scenario

    @spec-0061 @e2e
    Scenario: An empty results directory is refused
      Given the results directory exists and holds no Allure result
      When the results are read
      Then it refuses and says no test run wrote results there

    @spec-0062 @e2e
    Scenario: A results file that cannot be read is named, never skipped
      Given a results directory holding one result file that is not valid JSON
      When the results are read
      Then the report names that file as unreadable

    @spec-0063 @contract
    Scenario: The stack that wrote the results does not matter
      Given Allure results labelled with spec ids written by a JavaScript test run
      When the results are read
      Then the scenarios they name are verified exactly as if Clojure had written them

  Rule: Unit test results verify nothing

    @spec-0164 @contract
    Scenario: A result with no spec label counts toward nothing and is not reported as unknown
      Given an Allure result from a unit test, with no spec label
      When the results are read
      Then it counts toward no scenario
      And it is not listed as an unknown result
