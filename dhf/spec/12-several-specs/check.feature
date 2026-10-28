# Component Spec check, bounded context spec of the rdm-spec container: dhf/spec/model.edn.
# Slice 12-several-specs (ADR 0004).

# spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
# then its infrastructure, then the app's flows), each with its own needs,
# model, glossary and ids; rdm-spec read one spec folder, so the second could
# not sit beside the first. spec-32 gives each component the slice reaches
# its own scenario (ADR 0004).

@UN-001
Feature: Spec check
  Checks each spec on its own terms.

  Rule: Each spec is checked on its own terms

    @spec-0387 @e2e
    Scenario: Each spec is checked on its own terms, and one spec's finding fails the check
      Given two specs, auth and infra, where infra's feature file names a need its needs.edn does not hold, and auth's names UN-001, which only auth's needs.edn holds
      When "rdm-spec check" is run
      Then it exits 1 with one finding, naming infra's feature file and the need, and none about auth
      And a need, a component and a context resolve within their own spec only
