# Component Status, bounded context verification of the rdm-spec container: dhf/spec/model.edn.
# Slice 12-several-specs (ADR 0004).

# spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
# then its infrastructure, then the app's flows), each with its own needs,
# model, glossary and ids; rdm-spec read one spec folder, so the second could
# not sit beside the first. spec-32 gives each component the slice reaches
# its own scenario (ADR 0004).

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
