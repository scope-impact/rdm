# Component Ratify, bounded context ratification of the rdm-spec container: dhf/spec/model.edn.

@UN-007
Feature: Ratify
  Ratifies a build by a check the builder does not control.

  # The clean room is only where the builder works. What is judged is the
  # room's implementation, put into a fresh checkout of the cut, so the spec
  # and the configuration always come from the cut, never from the room, as
  # Löyly ratifies impl/ in its trusted repository
  # (loyly:harness/README.md@17d68d2, "The idea in five lines" 3).
  Rule: Ratify trusts only the cut

    @spec-0202 @e2e
    Scenario: Ratify reads the cycle before it reads anything else
      Given dhf/cycles/03/cycle.edn names the cut spec-3
      When "rdm-spec ratify /tmp/room 03" is run
      Then it ratifies against a fresh checkout of spec-3

    @spec-0203 @e2e
    Scenario: A cycle with no cycle.edn is refused
      Given no dhf/cycles/03/cycle.edn
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 2 and names the missing file

    @spec-0204 @e2e
    Scenario: The checkout's implementation is replaced by the room's, never merged with it
      Given the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file
      When "rdm-spec ratify /tmp/room 03" is run
      Then the checkout it ratifies holds no src/rdm_spec/old.clj
      And every configured implementation path holds exactly what the room holds

    @spec-0205 @e2e
    Scenario: Edits the builder made outside the implementation are not used
      Given the builder has edited dhf/harness/config.edn and a feature file in the room
      When "rdm-spec ratify /tmp/room 03" is run
      Then the configuration and the spec used are those of spec-3, unedited

  # Incident: a gate once reported success over a test command that had become
  # a no-op exiting 0 (loyly:harness/README.md@17d68d2, "The failure modes this
  # shape was learned from").
  Rule: Ratify passes on exactly one condition

    @spec-0206 @contract
    Scenario: The builder's tests are told where to write their results
      When "rdm-spec ratify /tmp/room 03" runs the declared test command
      Then the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec ratify was run in

    @spec-0207 @e2e
    Scenario: Ratify passes when the builder's tests pass, wrote results, and name every scenario
      Given the declared test command exits 0
      And it wrote Allure results into dhf/cycles/03/results
      And every stamped scenario not tagged @wip is named by at least one result
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 0

    @spec-0197 @e2e
    Scenario: A failing test command refuses ratification
      Given the declared test command exits 1
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 1 and says the test command failed, with its exit status

    @spec-0033 @e2e
    Scenario: Ratify requires results
      Given the declared test command exits 0 without writing any Allure result
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 1 and says a green exit with no results is not a run

    # Every scenario is tested before it counts as built (loyly:docs/wow/invariants.md@17d68d2, INV-5).
    @spec-0208 @e2e
    Scenario: A scenario no test names refuses ratification
      Given the stamped scenario @spec-0012 is not tagged @wip
      And no result in dhf/cycles/03/results is labelled spec=spec-0012
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 1 and names spec-0012

  # The grader grades the build that was ratified, and only that build.
  Rule: Ratify records what it ratified

    @spec-0215 @contract
    Scenario: Ratify writes ratified.edn with the cut, the room's tree and the outcome
      Given the room's implementation has the tree hash 4b825dc
      When "rdm-spec ratify /tmp/room 03" is run and passes
      Then dhf/cycles/03/ratified.edn holds:
        """
        {:cut "spec-3"
         :room-tree "4b825dc"
         :outcome :passed
         :checkout "dhf/cycles/03/build"}
        """
      And dhf/cycles/03/build is the ratified checkout, which the grader grades

    @spec-0216 @contract
    Scenario: A refused ratification is recorded with its reason
      When "rdm-spec ratify /tmp/room 03" is run and the test command exits 1
      Then dhf/cycles/03/ratified.edn holds :outcome :refused and the reason

    @spec-0217 @e2e
    Scenario: A cut holding an unstamped scenario is not ratified
      Given the spec at cut spec-3 holds a scenario with no id
      When "rdm-spec ratify /tmp/room 03" is run
      Then it exits 1 and names the file and line of the unstamped scenario
