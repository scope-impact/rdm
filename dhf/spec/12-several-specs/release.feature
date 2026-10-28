@UN-004
Feature: Release check
  Checks a release of every spec, or of the one named.

  Rule: A release reads every spec, or the one named

    @spec-0390 @e2e
    Scenario Outline: Release reads every spec, or the one named
      Given two specs, auth with every scenario verified and infra with one unverified
      When "<task>" is run
      Then it <answers>

      Examples:
        | task                                         | answers                                                       |
        | rdm-spec release --results R                 | refuses, naming infra's unverified scenario                   |
        | rdm-spec release --results R --spec auth     | passes                                                        |
