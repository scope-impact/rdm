# Component Status, container verification: dhf/spec/model.edn.

@UN-003
Feature: Status
  Derives verified, failed, unverified or planned for every stamped scenario.

  Rule: Planned work is a scenario tagged @wip

    @spec-0064 @e2e
    Scenario: The status report lists a scenario tagged @wip as planned
      Given a stamped scenario tagged @wip with no passing result
      When the status report runs
      Then it lists the scenario as planned

    @spec-0065 @e2e
    Scenario: Status is derived from the results, never written
      Given three stamped scenarios: one passing, one failing, one with no result
      When the status report runs
      Then it lists one as verified, one as failed and one as unverified
      And no file in the spec changes

  # The parser is ours. A construct it does not know is refused, never skipped:
  # a scenario the parser silently drops is a requirement nothing checks.
  Rule: The spec is written in a declared subset of Gherkin

    @spec-0066 @contract
    Scenario: A scenario outline is one requirement, verified by all its rows
      Given a stamped scenario outline with three example rows
      And Allure results for two rows passed and one failed
      When the results are read
      Then the scenario is failed

  # Incident: a test runner named its suites twice, once in imports and once in
  # a run list, so a forgotten suite was silently green.
  Rule: Allure results are the oracle, whoever wrote them

    @spec-0067 @contract
    Scenario: A passed result with the scenario's id verifies it
      Given a stamped scenario @spec-0007
      And an Allure result labelled spec=spec-0007 with status passed
      When the results are read
      Then the scenario is verified

    @spec-0068 @contract
    Scenario Outline: Any status but passed does not verify
      Given a stamped scenario @spec-0007
      And an Allure result labelled spec=spec-0007 with status <status>
      When the results are read
      Then the scenario is failed

      Examples:
        | status  |
        | failed  |
        | broken  |
        | skipped |
        | unknown |

    @spec-0069 @contract
    Scenario: One failing result among several fails the scenario
      Given a stamped scenario @spec-0007
      And two Allure results labelled spec=spec-0007, one passed and one failed
      When the results are read
      Then the scenario is failed

    @spec-0070 @contract
    Scenario: A scenario with no result is unverified, never left out
      Given ten stamped scenarios
      And Allure results for nine of them
      When the results are read
      Then the report counts ten scenarios
      And the tenth is unverified
