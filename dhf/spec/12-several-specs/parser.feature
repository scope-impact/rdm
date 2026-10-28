# Component Parser, bounded context spec of the rdm-spec container: dhf/spec/model.edn.
# Slice 12-several-specs (ADR 0004).

# spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
# then its infrastructure, then the app's flows), each with its own needs,
# model, glossary and ids; rdm-spec read one spec folder, so the second could
# not sit beside the first. spec-32 gives each component the slice reaches
# its own scenario (ADR 0004).

@UN-001
Feature: Parser
  Reads several specs, one folder each.

  Rule: A project keeps one spec, or several, one folder each

    @spec-0385 @contract
    Scenario: Several specs are the configuration's :specs, each a folder of its own
      Given a configuration whose :specs is [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/infra" :id-prefix "infra"}]
      When "rdm-spec check" is run
      Then each spec is read from its folder: its feature files, model.edn, events.edn, needs.edn, glossary.md, ids.edn, and its domain.edn where it keeps one
      And a configuration with no :specs reads one spec, from :spec-dir and the paths it names, as before

    @spec-0386 @e2e
    Scenario Outline: A configuration of several specs rdm-spec cannot read apart is refused before anything runs
      Given a configuration whose :specs <holds>
      When "rdm-spec check" is run
      Then it exits 2 and says <why>

      Examples:
        | holds                                              | why                                                              |
        | two specs named "auth"                             | a spec's name is declared once                                   |
        | two specs with the id prefix "auth"                | an id prefix is declared once, so no two scenarios share an id   |
        | two specs in one folder                            | a folder holds one spec                                          |
        | a spec whose folder does not exist                 | names the spec and its folder                                    |
