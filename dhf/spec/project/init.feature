# Component Init, bounded context project of the rdm-spec container: dhf/spec/model.edn.

@UN-009
Feature: Init
  Writes a new project's skeleton into a path that does not exist yet.

  Rule: Init starts a project from nothing

    @spec-0086 @contract
    Scenario: Init writes the skeleton and names what it wrote
      Given no directory at the path "sauna"
      When "rdm-spec init sauna" is run
      Then "sauna" is a git repository holding exactly:
        | path                              | holds                                                                    |
        | dhf/spec/model.edn                | a model with the system and nothing else                                 |
        | dhf/spec/events.edn               | an empty event catalogue                                                 |
        | dhf/spec/needs.edn                | no needs                                                                 |
        | dhf/spec/ids.edn                  | the ledger at 0                                                          |
        | dhf/spec/glossary.md              | an empty glossary                                                        |
        | dhf/spec/README.md                | the spec's conventions                                                   |
        | dhf/harness/config.edn            | the id prefix, the allow-list dhf/spec, dhf/harness, dhf/raw, dhf/wiki and dhf/decisions, and no implementation paths |
        | dhf/raw/README.md                 | the rules for raw sources                                                |
        | dhf/raw/sources.edn               | no sources                                                               |
        | dhf/wiki/index.md                 | the wiki's rules and an empty list of pages                              |
        | dhf/decisions/README.md           | how decisions are recorded                                               |
        | .githooks/pre-commit              | a hook that runs "rdm-spec check", with core.hooksPath set to .githooks  |
        | .github/workflows/cut.yml         | a workflow that runs "rdm-spec cut" on every push to the default branch  |
        | .gitignore                        | dhf/allure-results/, dhf/cycles/*/results/, dhf/cycles/*/build/, site-src/ and site/ |
      And init lists every file it wrote and the next steps

    @spec-0087 @e2e
    Scenario: Init refuses a path that already exists
      Given a directory at the path "sauna"
      When "rdm-spec init sauna" is run
      Then it exits 2 and names the path
      And nothing in "sauna" changes

    @spec-0089 @e2e
    Scenario: The id prefix is chosen at init
      When "rdm-spec init sauna --id-prefix sauna" is run
      Then the harness configuration sets the id prefix to "sauna"

  Rule: A new project is green before anything is written in it

    @spec-0090 @e2e
    Scenario: The check passes on a skeleton nobody has touched
      Given a project just initialised at "sauna"
      When "rdm-spec check" is run in "sauna"
      Then it passes
