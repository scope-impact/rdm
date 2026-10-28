# Component Trace, bounded context verification of the rdm-spec container: dhf/spec/model.edn.
# Slice 12-several-specs (ADR 0004).

# spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
# then its infrastructure, then the app's flows), each with its own needs,
# model, glossary and ids; rdm-spec read one spec folder, so the second could
# not sit beside the first. spec-32 gives each component the slice reaches
# its own scenario (ADR 0004).

@UN-003
Feature: Trace
  Traces a need or a scenario in whichever spec holds it.

  Rule: A trace reads the spec that holds what it traces

    @spec-0409 @e2e
    Scenario: A need is traced within its own spec
      Given two specs, auth and infra, each with a need UN-001 and a scenario tagged with it
      When "rdm-spec trace UN-001 --spec infra --results R" is run
      Then it lists infra's scenario and its status, and none of auth's
      And "rdm-spec trace UN-001 --results R", with no --spec, exits 2 and names both specs that hold UN-001
      And a scenario id, unique across specs, is traced with no --spec
