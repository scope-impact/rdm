# Component Release check, bounded context verification of the rdm-spec container: dhf/spec/model.edn.

@UN-004
Feature: Release check
  Refuses a release while any scenario is not verified.

  Rule: A release needs every scenario verified

    @spec-0059 @e2e
    Scenario: The release check refuses while any scenario is tagged @wip
      Given a stamped scenario tagged @wip, and a passing result for every other stamped scenario
      When "rdm-spec release" is run
      Then it exits 1 and names the scenario's id

    @spec-0188 @e2e
    Scenario: The release check refuses a failed scenario
      Given a stamped scenario whose result failed
      When "rdm-spec release" is run
      Then it exits 1 and names the scenario's id and its status

    @spec-0189 @e2e
    Scenario: The release check refuses an unverified scenario
      Given a stamped scenario with no result
      When "rdm-spec release" is run
      Then it exits 1 and names the scenario's id and its status

    @spec-0190 @e2e
    Scenario: The release check passes when every scenario is verified
      Given every stamped scenario has a passing result and none is tagged @wip
      When "rdm-spec release" is run
      Then it exits 0

  # A clause no document names is unverified the way a scenario with no result
  # is (dhf/spec/compliance/gap.feature).
  @UN-013
  Rule: Under the regulated profile, a release needs every clause named

    @spec-0267 @e2e
    Scenario: The release check refuses a clause no audit tag names
      Given a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names
      When "rdm-spec release" is run
      Then it exits 1 and names the clause and its clause list
