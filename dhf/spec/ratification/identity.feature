# Component Identity, container ratification: dhf/spec/model.edn.

@UN-007
Feature: Identity
  Proves the thing answering is this project.

  # Incident: an orphaned process from a dead run once answered a health probe,
  # so a scoring run graded the wrong process. Incident: a gate once reported
  # success over a test command that had become a no-op exiting 0.
  Rule: Done is ratified by a gate the builder does not control

    @spec-0035 @e2e
    Scenario: The identity probe proves the thing answering is this project
      Given the project's identity probe
      And a different program answering on the configured port
      When the gate runs the probe
      Then the gate fails with what the probe printed
