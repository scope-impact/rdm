@UN-002 @UN-009
Feature: Hooks
  Runs the check on every commit.

  Rule: Every commit is checked

    @spec-0025 @e2e
    Scenario: The pre-commit hook runs the check
      Given the pre-commit hook is installed
      When a commit is made
      Then the hook runs "rdm-spec check"
      And the commit is refused when the check exits other than 0
