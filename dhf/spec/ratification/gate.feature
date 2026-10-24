# Component Gate, container ratification: dhf/spec/model.edn.

@UN-007
Feature: Gate
  Ratifies a build by a gate the builder does not control.

  # A step definition that loads implementation code pins the implementation's
  # internal design in place, and a regeneration graded by it measures nothing.
  Rule: Step definitions drive the public surface only

    # Placed here until the event storm's hotspot H10 decides where the
    # Gherkin runner lives (dhf/storm/2026-10-24-rdm.md).
    @spec-0032 @e2e
    Scenario: A step with no definition fails the run, not the scenario's absence
      Given a stamped scenario with a step no step definition matches
      When the acceptance run executes
      Then the scenario's Allure result is broken
      And the result names the unmatched step

  # Incident: an orphaned process from a dead run once answered a health probe,
  # so a scoring run graded the wrong process. Incident: a gate once reported
  # success over a test command that had become a no-op exiting 0.
  Rule: Done is ratified by a gate the builder does not control

    @spec-0033 @e2e
    Scenario: The gate runs the declared test command and requires results
      Given the configuration declares the test command
      When the gate runs it and it exits 0 without writing any Allure result
      Then the gate fails and says a green exit with no results is not a run

  Rule: The previous release judges the next

    @spec-0034 @e2e
    Scenario: Regenerated RDM is ratified by the pinned release
      Given the configuration pins RDM release 0.4.0 as the judge
      And a regenerated RDM in the clean room
      When the regeneration is ratified
      Then release 0.4.0 runs the gate
      And the regenerated build runs only as the thing under test
