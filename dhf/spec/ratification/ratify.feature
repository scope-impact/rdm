# Component Ratify, bounded context ratification of the rdm-spec container: dhf/spec/model.edn.

@UN-007
Feature: Ratify
  Ratifies a build by a check the builder does not control.

  # The clean room is only where the builder works. What is judged is the
  # room's implementation, put into a fresh checkout of the cut, so the spec,
  # the configuration and the step definitions always come from the cut, never
  # from the room (as Löyly ratifies impl/ in the trusted repository:
  # loyly:harness/README.md@17d68d2, "The idea in five lines" 3).
  Rule: Ratify trusts only the cut

    @spec-0194 @e2e
    Scenario: Only the implementation comes back from the room
      Given a clean room built from cut spec-3
      And the builder has edited a step definition and the test command in it
      When "rdm-spec ratify /tmp/room 03" is run
      Then the room's implementation paths are put into a fresh checkout of spec-3
      And the step definitions and the configuration used are those of spec-3, unedited

    @spec-0195 @contract
    Scenario: The step definitions run the build's own entry point
      Given the configuration declares the command under test as "bb -m rdm-spec.main"
      When "rdm-spec ratify /tmp/room 03" runs the scenarios
      Then every step that runs rdm-spec runs the build's "bb -m rdm-spec.main" in the checkout, never the installed rdm-spec

  # Incident: a gate once reported success over a test command that had become
  # a no-op exiting 0 (loyly:harness/README.md@17d68d2, "The failure modes this
  # shape was learned from").
  Rule: Ratify passes on exactly one condition

    @spec-0196 @e2e
    Scenario: Ratify passes when the test command passes, wrote results, and the steps are black box
      Given the declared test command exits 0
      And it wrote Allure results into the cycle's results directory
      And every step definition passes the black-box check
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 0

    @spec-0197 @e2e
    Scenario: A failing test command refuses ratification and names what failed
      Given the declared test command exits 1 because one scenario failed
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 1 and names the failed scenario

    @spec-0033 @e2e
    Scenario: Ratify requires results
      Given the declared test command exits 0 without writing any Allure result
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 1 and says a green exit with no results is not a run

    # Decided by the event storm's hotspot H9: unit tests run in the declared
    # test command and can refuse a build, but never verify a scenario.
    @spec-0161 @e2e
    Scenario: A failing unit test refuses ratification, even when every scenario passed
      Given a build whose every scenario passes and one of whose unit tests fails
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 1 and names the failing unit test
