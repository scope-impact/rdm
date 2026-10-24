# Component Init, container project: dhf/spec/model.edn.

@UN-009
Feature: Init
  Writes a new project's skeleton into a directory that does not exist yet.

  Rule: Init starts a project from nothing

    @spec-0086 @e2e
    Scenario: Init writes the skeleton and names what it wrote
      Given no directory at the path "sauna"
      When a project is initialised at "sauna"
      Then "sauna" holds the spec skeleton: an empty model, event catalogue, needs and ledger, the glossary and the spec's README
      And it holds the harness configuration, the decisions directory and the task file
      And init lists every file it wrote and the next steps

    @spec-0087 @e2e
    Scenario: Init refuses a path that already exists
      Given a directory at the path "sauna"
      When a project is initialised at "sauna"
      Then init refuses and names the path
      And nothing in "sauna" changes

    @spec-0088 @e2e
    Scenario: A new project is a git repository with the hook installed
      When a project is initialised at "sauna"
      Then "sauna" is a git repository
      And its pre-commit hook runs the check task

    @spec-0089 @e2e
    Scenario: The id prefix is chosen at init
      When a project is initialised at "sauna" with the id prefix "sauna"
      Then the harness configuration sets the id prefix to "sauna"

  Rule: A new project is green before anything is written in it

    @spec-0090 @e2e
    Scenario: The check passes on a skeleton nobody has touched
      Given a project just initialised at "sauna"
      When the check task runs in "sauna"
      Then it passes

    @spec-0091 @e2e
    Scenario: Init needs no network
      Given no network is reachable
      When a project is initialised at "sauna"
      Then the skeleton is written in full

  Rule: A new project cuts every merge

    @spec-0158 @e2e
    Scenario: A new project's CI cuts every merge to the default branch
      When a project is initialised at "sauna"
      Then "sauna" holds a CI workflow that runs the cut task on every push to the default branch
