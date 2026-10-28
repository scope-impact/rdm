@UN-003
Feature: Results reader
  Reads a run's Allure results, whatever stack wrote them, naming what it cannot read.

  Rule: Allure results are the oracle, whoever wrote them

    @spec-0060 @contract
    Scenario: A result naming no scenario is reported, not counted
      Given an Allure result labelled spec=spec-9999
      And no scenario in the spec is tagged @spec-9999
      When "rdm-spec status" is run
      Then the report lists spec-9999 as an unknown result
      And it counts toward no scenario

    @spec-0456 @contract
    Scenario: A result naming no scenario is also a warning line
      Given a results directory DIR holding a passing result F labelled spec=spec-9999, and no scenario tagged @spec-9999
      When "rdm-spec status --results DIR" is run
      Then it exits 0, its result's :unknown-results is [{:file F :spec "spec-9999"}], F as DIR/name
      And its error stream holds one warning line about it, F: warning: RULE: what, naming spec-9999

    @spec-0061 @e2e
    Scenario: An empty results directory is refused
      Given the results directory exists and holds no Allure result
      When "rdm-spec status" is run
      Then it exits 2 and says no test run wrote results there

    @spec-0062 @e2e
    Scenario: A results file that cannot be read is named, never skipped
      Given a results directory DIR holding one result file F, as DIR/name, that is not valid JSON, beside a passing result for every stamped scenario
      When "rdm-spec status --results DIR" is run
      Then it exits 0, and the report names F as unreadable, in one warning line on the error stream, F: warning: RULE: what
      And its result is spec-0307's shape with :unreadable-files [F] added

  Rule: A result with no spec label verifies nothing

    @spec-0164 @contract
    Scenario: A result with no spec label counts toward nothing and is not reported as unknown
      Given an Allure result from a unit test, with no spec label
      When "rdm-spec status" is run
      Then it counts toward no scenario
      And it is not listed as an unknown result

  Rule: What decides a status, and what is only kept

    @spec-0312 @contract
    Scenario: A result counts by its status, its spec label and an outline row's parameters alone
      Given two passing Allure results labelled spec=spec-0012, one with attachments, links and extra labels, and one with none
      When "rdm-spec status" is run
      Then spec-0012 is verified, and the other elements change nothing

    @spec-0313 @contract
    Scenario: A result whose status is not one of Allure's is unreadable
      Given an Allure result labelled spec=spec-0012 whose status is "green"
      When "rdm-spec status" is run
      Then the report names its file as unreadable, saying the status must be passed, failed, broken, skipped or unknown
