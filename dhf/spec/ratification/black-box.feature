# Component Black-box check, container ratification: dhf/spec/model.edn.

@UN-007
Feature: Black-box check
  Keeps step definitions on the public surface.

  # A step definition that loads implementation code pins the implementation's
  # internal design in place, and a regeneration graded by it measures nothing.
  # The one exception is the runner's step API, which step definitions are
  # written against (dhf/spec/kernel/runner.feature).
  Rule: Step definitions drive the public surface only

    @spec-0027 @e2e
    Scenario: A step definition that requires an implementation namespace fails ratification
      Given the configuration declares the implementation namespace prefix "rdm-spec."
      And a step definition namespace requires rdm-spec.spec.parser
      When "rdm-spec ratify" is run
      Then it exits 1 and names the step file and the required namespace

    @spec-0028 @e2e
    Scenario: A step definition may run the command, touch files and use the step API
      Given a step definition namespace that requires only babashka.process, babashka.fs, clojure.string and the configured step API namespace
      When "rdm-spec ratify" checks the step definitions
      Then the step file passes the black-box check
