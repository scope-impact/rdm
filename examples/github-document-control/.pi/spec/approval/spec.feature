Feature: Approval
  The document control system's requirements in this context: each stamped scenario is
  one requirement, verified by the acceptance test that claims it.

  @allium-guarantee.IndependentApproval @allium-guarantee.Part11Controls
  @item-01-001
  Scenario: Changes to the default branch shall require a pull request with at least one code-owner approval, verified commit signatures, passing status checks, and protection against history rewriting and branch deletion, enforced by a repository ruleset kept as configuration code.

  @allium-guarantee.CurrentRevision @allium-guarantee.Part11Controls
  @item-01-002
  Scenario: Repository merge behavior shall be declared as configuration code: pull requests merge only by merge commit so the reviewed SHA is preserved in history, squash and rebase merges are disabled, head branches are deleted on merge, and the setup script shall apply and drift-check these settings against the live repository.

  @allium-guarantee.IndependentApproval @allium-guarantee.Part11Controls
  @item-01-003
  Scenario: Every pull request to the default branch shall run the design controls (the acceptance tests, verification, the release gate and graph validation) through RDM's reusable gates pinned to a released revision, and the Part 11 gap analysis, and the ruleset shall require exactly the checks that workflow reports.

  @allium-guarantee.IndependentApproval @allium-guarantee.Part11Controls
  @item-01-004
  Scenario: A scheduled audit shall compare the live ruleset and repository settings with the checked-in configuration at least daily, and shall fail on any difference.
