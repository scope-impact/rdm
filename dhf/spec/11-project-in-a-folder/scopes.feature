@UN-006
Feature: Scopes
  Lists what a spec change touched in the project's folder.

  Rule: A change outside the project's folder is no scope of it

    @spec-0407 @e2e
    Scenario: Scopes read the change within the project's folder only
      Given a repository whose project is its folder wallet/, and the cuts spec-2 and spec-3, between which one of wallet/'s feature files and a feature file outside wallet/ changed
      When "rdm-spec scopes spec-2 spec-3" is run in wallet/
      Then it lists the scope of wallet/'s changed feature file only, and names no file outside wallet/
