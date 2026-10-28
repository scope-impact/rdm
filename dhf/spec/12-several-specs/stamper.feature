# Component Stamper, bounded context spec of the rdm-spec container: dhf/spec/model.edn.
# Slice 12-several-specs (ADR 0004).

# spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
# then its infrastructure, then the app's flows), each with its own needs,
# model, glossary and ids; rdm-spec read one spec folder, so the second could
# not sit beside the first. spec-32 gives each component the slice reaches
# its own scenario (ADR 0004).

@UN-001
Feature: Stamper
  Stamps each spec from its own ledger.

  Rule: Each spec stamps from its own ledger

    @spec-0388 @e2e
    Scenario: Each spec stamps from its own ledger, with its own prefix
      Given two specs, auth with 3 stamped scenarios and infra with none, each with one unstamped scenario
      When "rdm-spec stamp" is run
      Then auth's is stamped @auth-0004 from dhf/sdd/auth/ids.edn, and infra's @infra-0001 from dhf/sdd/infra/ids.edn
