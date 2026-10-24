# Component Black-box check, container ratification: dhf/spec/model.edn.

@UN-007
Feature: Black-box check
  Keeps step definitions on the public surface.

  # A step definition that loads implementation code pins the implementation's
  # internal design in place, and a regeneration graded by it measures nothing.
  Rule: Step definitions drive the public surface only

    @spec-0027 @e2e
    Scenario: A step definition that requires an implementation namespace fails the gate
      Given the configuration declares the implementation namespace prefix "rdm-spec."
      And a step definition namespace requires rdm-spec.spec.reader
      When the gate runs
      Then it fails and names the step file and the required namespace

    @spec-0028 @e2e
    Scenario: A step definition may run the command and touch files
      Given a step definition namespace that requires only babashka.process, babashka.fs and the harness support namespace
      When the gate runs
      Then the step file passes the black-box check
