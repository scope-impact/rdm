@UN-003
Feature: Status
  Derives verified, failed, unverified or planned for every stamped scenario.
  Status is a report: it exits 0 whatever the statuses; only the release
  check refuses.

  Rule: Status's result has one shape

    @spec-0307 @contract
    Scenario: The status result is exactly this shape
      Given a project with the stamped scenarios @spec-0001 "One", @spec-0002 "Two" and @spec-0003 "Three", in dhf/spec/a/b.feature at lines 4, 8 and 12, and @spec-0003 tagged @wip
      And a results directory DIR holding a passing result for @spec-0001 and a passing result labelled spec=spec-0099
      When "rdm-spec status --results DIR" is run
      Then it exits 0, and its standard output, read as EDN, is exactly {:status {:results DIR :scenarios [{:id "spec-0001" :title "One" :file "dhf/spec/a/b.feature" :line 4 :status :verified} {:id "spec-0002" :title "Two" :file "dhf/spec/a/b.feature" :line 8 :status :unverified} {:id "spec-0003" :title "Three" :file "dhf/spec/a/b.feature" :line 12 :status :planned}] :counts {:verified 1 :failed 0 :unverified 1 :planned 1} :unknown-results [{:file F :spec "spec-0099"}]}}, F the result's file as DIR names it (DIR/name), as trace and release name a result, DIR as given

  Rule: Planned work is a scenario tagged @wip

    @spec-0064 @e2e
    Scenario: The status report lists a scenario tagged @wip as planned
      Given a stamped scenario tagged @wip, with no result, and a passing result for another stamped scenario
      When "rdm-spec status" is run
      Then it lists the scenario as planned

    @spec-0191 @e2e
    Scenario: A scenario tagged @wip is planned whatever its results say
      Given a stamped scenario tagged @wip, with results
      And an Allure result for it with status skipped, and another with status passed
      When "rdm-spec status" is run
      Then it lists the scenario as planned, not as verified, failed or unverified

    @spec-0065 @e2e
    Scenario: Status is derived from the results, never written
      Given three stamped scenarios: one passing, one failing, one with no result
      When "rdm-spec status" is run
      Then it lists one as verified, one as failed and one as unverified
      And no file in the spec changes

  Rule: An outline is one requirement

    @spec-0066 @contract
    Scenario: A scenario outline is one requirement, verified by all its rows
      Given a stamped scenario outline with three example rows
      And Allure results for two rows passed and one failed
      When "rdm-spec status" is run
      Then the scenario is failed

    @spec-0302 @contract
    Scenario: Results that name no row do not verify an outline
      Given a stamped scenario outline with three example rows
      And three passing Allure results labelled with its id, none of which names its row
      When "rdm-spec status" is run
      Then the scenario is unverified

    @spec-0285 @contract
    Scenario: An outline with a row that has no result is unverified
      Given a stamped scenario outline with three example rows
      And Allure results for two rows, both passed, and none for the third
      When "rdm-spec status" is run
      Then the scenario is unverified

  Rule: Allure results are the oracle, whoever wrote them

    @spec-0067 @contract
    Scenario: A passed result with the scenario's id verifies it
      Given a stamped scenario @spec-0007
      And an Allure result labelled spec=spec-0007 with status passed
      When "rdm-spec status" is run
      Then the scenario is verified

    @spec-0068 @contract
    Scenario Outline: Any status but passed does not verify
      Given a stamped scenario @spec-0007
      And an Allure result labelled spec=spec-0007 with status <status>
      When "rdm-spec status" is run
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
      When "rdm-spec status" is run
      Then the scenario is failed

    @spec-0070 @contract
    Scenario: A scenario with no result is unverified, never left out
      Given ten stamped scenarios
      And Allure results for nine of them
      When "rdm-spec status" is run
      Then the report counts ten scenarios
      And the tenth is unverified
