# Component Ratify, container ratification: dhf/spec/model.edn.

@UN-007
Feature: Ratify
  Ratifies a build in a clean room by a check the builder does not control.

  # Incident: a gate once reported success over a test command that had become
  # a no-op exiting 0 (skallinen/loyly harness/README.md@17d68d2, "The failure
  # modes this shape was learned from").
  Rule: Done is ratified by a check the builder does not control

    @spec-0033 @e2e
    Scenario: Ratify runs the declared test command and requires results
      Given the configuration declares the test command
      When "rdm-spec ratify /tmp/room" runs it and it exits 0 without writing any Allure result
      Then it exits 1 and says a green exit with no results is not a run

    @spec-0181 @e2e
    Scenario: The installed rdm-spec ratifies, never the regenerated one
      Given a clean room whose regenerated build includes its own rdm-spec
      When "rdm-spec ratify /tmp/room" is run
      Then the installed rdm-spec runs the checks
      And the regenerated build runs only as the thing under test

  Rule: Ratify does not know who built

    @spec-0145 @e2e @UN-012
    Scenario: The same build gets the same verdict whether a human or an agent built it
      Given two clean rooms from the same cut holding byte-identical builds
      And one cycle declares a human builder and the other an agent builder
      When "rdm-spec ratify" is run on both
      Then both verdicts are the same, with the same reasons

  # Decided by the event storm's hotspot H9 (dhf/storm/2026-10-24-rdm.md): unit
  # tests run in the declared test command and can refuse a build, but never
  # verify a scenario.
  Rule: Ratify runs the unit tests too

    @spec-0161 @e2e
    Scenario: A failing unit test refuses ratification, even when every scenario passed
      Given a build whose every scenario passes and one of whose unit tests fails
      When "rdm-spec ratify /tmp/room" is run
      Then it exits 1 and names the failing unit test
