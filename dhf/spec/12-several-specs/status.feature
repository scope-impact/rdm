@UN-003
Feature: Status
  Derives the status of every scenario of every spec.

  Rule: Status reads every spec

    @spec-0408 @e2e
    Scenario: Status lists every scenario of every spec, each with its spec's name
      Given two specs, auth with every scenario verified and infra with one unverified
      When "rdm-spec status --results R" is run
      Then it lists every stamped scenario of both specs, each with its status and its spec's name
      And "rdm-spec status --results R --spec auth" lists only auth's
