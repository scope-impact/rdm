# Component Init, bounded context project of the rdm-spec container: dhf/spec/model.edn.

@UN-009
Feature: Init
  Writes a new project's skeleton into a directory that does not exist yet.

  Rule: Init starts a project from nothing

    @spec-0086 @e2e
    Scenario: Init writes the skeleton and names what it wrote
      Given no directory at the path "sauna"
      When "rdm-spec init sauna" is run
      Then "sauna" holds the skeleton: dhf/spec with an empty model, event catalogue, needs and ledger, a glossary and a README; dhf/harness/config.edn; dhf/raw, dhf/wiki and dhf/steps, each with a README; dhf/decisions; the pre-commit hook; and .github/workflows/cut.yml
      And init lists every file it wrote and the next steps

    @spec-0087 @e2e
    Scenario: Init refuses a path that already exists
      Given a directory at the path "sauna"
      When "rdm-spec init sauna" is run
      Then init refuses and names the path
      And nothing in "sauna" changes

    @spec-0088 @e2e
    Scenario: A new project is a git repository with the hook installed
      When "rdm-spec init sauna" is run
      Then "sauna" is a git repository
      And its pre-commit hook runs "rdm-spec check"

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

  Rule: A new project cuts every merge

    @spec-0158 @e2e
    Scenario: A new project's CI cuts every merge to the default branch
      When "rdm-spec init sauna" is run
      Then "sauna" holds the GitHub Actions workflow .github/workflows/cut.yml, which runs "rdm-spec cut" on every push to the default branch
