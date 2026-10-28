@UN-006
Feature: Fence
  Builds a clean room from a cut of a project that is one folder of its repository.

  Rule: A cut of a project in a folder is the folder as committed

    @spec-0369 @e2e
    Scenario: A cut is read as committed, however the project's working tree differs
      Given a project in the folder wallet/ whose working tree has an uncommitted change to a feature file and an untracked file, and whose HEAD is not the commit tagged spec-3
      When "rdm-spec fence ROOM spec-3" is run in wallet/
      Then ROOM holds the feature file as committed at spec-3, and neither the change nor the untracked file
