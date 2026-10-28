@UN-004
Feature: Release check
  Refuses a release while any scenario is not verified.

  Rule: A release needs every scenario verified

    @spec-0059 @e2e
    Scenario: The release check refuses while any scenario is tagged @wip
      Given a stamped scenario tagged @wip, and a passing result for every other stamped scenario
      When "rdm-spec release" is run
      Then it exits 1 and names the scenario's id

    @spec-0188 @e2e
    Scenario: The release check refuses a failed scenario
      Given a stamped scenario whose result failed
      When "rdm-spec release" is run
      Then it exits 1 and names the scenario's id and its status

    @spec-0189 @e2e
    Scenario: The release check refuses an unverified scenario
      Given a stamped scenario with no result
      When "rdm-spec release" is run
      Then it exits 1 and names the scenario's id and its status

    @spec-0190 @e2e
    Scenario: The release check passes when every scenario is verified
      Given every stamped scenario has a passing result and none is tagged @wip
      When "rdm-spec release" is run
      Then it exits 0

  Rule: The release check's result has one shape

    @spec-0453 @contract
    Scenario Outline: The release check prints its result whether it passes or refuses
      Given the stamped scenarios @spec-0001, at line 4 of dhf/spec/a/b.feature, and @spec-0002, and a results directory DIR in which @spec-0001 <has>, and @spec-0002 passed
      When "rdm-spec release --results DIR" is run
      Then it exits <exit>, and its standard output, read as EDN, is exactly {:release {:passed? <passed> :scenarios 2 :counts {:verified <v> :failed <f> :unverified <u> :planned 0} :refused <r>}}, :refused the number of its findings
      And its error stream holds <line>

      Examples:
        | has        | exit | passed | v | f | u | r | line                                                                                     |
        | passed     | 0    | true   | 2 | 0 | 0 | 0 | no line                                                                                  |
        | failed     | 1    | false  | 1 | 1 | 0 | 1 | one finding, dhf/spec/a/b.feature:4: RULE: what, naming spec-0001 and its status failed     |
        | no result  | 1    | false  | 1 | 0 | 1 | 1 | one finding, dhf/spec/a/b.feature:4: RULE: what, naming spec-0001 and its status unverified |

  Rule: A release reads every result, and refuses on one it cannot read

    @spec-0454 @e2e
    Scenario Outline: An unknown result does not refuse a release; an unreadable one does
      Given a passing result for every stamped scenario in a results directory DIR, and beside them <extra>, F as DIR/name
      When "rdm-spec release --results DIR" is run
      Then it exits <exit>, and its error stream holds <line>

      Examples:
        | extra                                       | exit | line                                                           |
        | a passing result F labelled spec=spec-9999   | 0    | one warning line, F: warning: RULE: what, naming spec-9999      |
        | a result file F that is not valid JSON       | 1    | one finding, F: RULE: what, naming F as unreadable              |

    @spec-0455 @e2e
    Scenario Outline: A --spec or --slice that names nothing is refused
      Given <project>
      When "rdm-spec release --results DIR <flag>" is run, DIR a results directory with a result in it
      Then it exits 2, names <names>, and prints nothing on standard output

      Examples:
        | project                                              | flag            | names                              |
        | a configuration whose :specs declares auth and infra | --spec nope     | nope, and the specs auth and infra |
        | a configuration with :spec-layout :slices            | --slice 99-none | 99-none                            |
