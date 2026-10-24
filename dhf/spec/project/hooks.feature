# Component Hooks, container project: dhf/spec/model.edn.

@UN-002 @UN-009
Feature: Hooks
  Runs the check on every commit.

  Rule: Everything runs through one task interface

    @spec-0025 @e2e
    Scenario: The pre-commit hook runs the check, and needs no shell
      Given the pre-commit hook is installed
      When a commit is made
      Then the hook runs the check task through Babashka
      And the commit is refused when the check fails
