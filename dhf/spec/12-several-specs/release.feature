# Component Release check, bounded context verification of the rdm-spec container: dhf/spec/model.edn.
# Slice 12-several-specs (ADR 0004).

# spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
# then its infrastructure, then the app's flows), each with its own needs,
# model, glossary and ids; rdm-spec read one spec folder, so the second could
# not sit beside the first. spec-32 gives each component the slice reaches
# its own scenario (ADR 0004).

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
