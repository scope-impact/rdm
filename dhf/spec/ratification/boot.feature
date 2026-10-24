# Component Boot, container ratification: dhf/spec/model.edn.

@UN-007
Feature: Boot
  Starts the build on a free port within the boot budget.

  # Incident: an orphaned process from a dead run once answered a health probe,
  # so a scoring run graded the wrong process. Incident: a gate once reported
  # success over a test command that had become a no-op exiting 0.
  Rule: Done is ratified by a gate the builder does not control

    @spec-0029 @e2e
    Scenario: The gate refuses when the port is already held
      Given another process holds the configured port
      When the gate is asked to start a container that serves on it
      Then it refuses before starting anything and names the port

    @spec-0030 @e2e
    Scenario: A container that does not answer within the boot budget fails
      Given the configuration declares a boot budget of 10 seconds
      And a container that does not answer within it
      When the gate starts the container
      Then it fails and names the budget in seconds

    @spec-0031 @e2e
    Scenario: A green gate reports the margin left in its boot budget
      Given a container that answers after 1 second of a 10 second budget
      When the gate starts the container
      Then the gate reports how much of the budget the boot used
