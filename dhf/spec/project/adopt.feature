# Component Adopt, container project: dhf/spec/model.edn.

@UN-009
Feature: Adopt
  Adds the skeleton to an existing repository, never overwriting a file.

  Rule: Adopt adds and never overwrites

    @spec-0079 @e2e
    Scenario: Adopt adds the skeleton beside what is already there
      Given a git repository holding source code and a README
      When the repository is adopted
      Then it holds the spec skeleton, the harness configuration and the decisions directory
      And its source code and README are unchanged
      And adopt lists every file it wrote

    @spec-0080 @e2e
    Scenario: A destination that already exists is skipped and named
      Given a git repository that already holds dhf/spec/glossary.md
      When the repository is adopted
      Then dhf/spec/glossary.md is unchanged
      And adopt names it as kept

    @spec-0081 @e2e
    Scenario: A symbolic link at a destination counts as existing
      Given a git repository where dhf/harness/config.edn is a symbolic link
      When the repository is adopted
      Then the link and its target are unchanged
      And adopt names the link as kept

    @spec-0082 @e2e
    Scenario: An existing ignore file is kept, and the lines to add are named
      Given a git repository with its own .gitignore
      When the repository is adopted
      Then the .gitignore is unchanged
      And adopt names the lines it would have added

    @spec-0083 @e2e
    Scenario: Adopting twice changes nothing
      Given a repository that has been adopted
      When it is adopted again
      Then no file changes
      And adopt names every destination as kept

    @spec-0084 @e2e
    Scenario: Adopt refuses a directory that is not a git repository
      Given a directory that is not a git repository
      When it is adopted
      Then adopt refuses, says the hook needs a git repository, and writes nothing

  Rule: Adopt never reads the existing code into the spec

    @spec-0085 @e2e
    Scenario: The adopted spec is empty whatever the repository holds
      Given a git repository holding source code and tests
      When the repository is adopted
      Then the model, the needs and the ledger are empty
      And no feature file is written
