# Component Checklist, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
# Slice 12-several-specs (ADR 0004).

# spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
# then its infrastructure, then the app's flows), each with its own needs,
# model, glossary and ids; rdm-spec read one spec folder, so the second could
# not sit beside the first. spec-32 gives each component the slice reaches
# its own scenario (ADR 0004).

@UN-008
Feature: Checklist
  Derives a checklist of one spec, or of every spec.

  Rule: A cycle may be scoped to one spec

    @spec-0389 @e2e
    Scenario: A cycle may be scoped to one spec
      Given two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is [{:spec "auth"}]
      When "rdm-spec checklist 03" is run, and verify's tests check reads the cycle's results
      Then the checklist holds one item per stamped scenario of auth and none of infra
      And verify requires a result for every scenario of auth, and none for infra's
