# Component Gate, container ratification: dhf/spec/model.edn.

@UN-007
Feature: Gate
  Ratifies a build by a gate the builder does not control.

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
    Scenario: Regenerated rdm-spec is ratified by the pinned release
      Given the configuration pins rdm-spec release 0.4.0 as the judge
      And a regenerated rdm-spec in the clean room
      When the regeneration is ratified
      Then release 0.4.0 runs the gate
      And the regenerated build runs only as the thing under test

  Rule: The gate does not know who built

    @spec-0145 @e2e @UN-012
    Scenario: The same build gets the same verdict whether a human or an agent built it
      Given two clean rooms from the same cut holding byte-identical builds
      And one cycle declares a human builder and the other an agent builder
      When the gate ratifies both
      Then both verdicts are the same, with the same reasons

  # Decided by the event storm's hotspot H7 (dhf/storm/2026-10-24-rdm.md). The
  # first build has no previous release to judge it, so it is the one build
  # ratified unjudged, and says so; its reviewer's acceptance is what stands in
  # for a judge, once.
  Rule: Before the first release, nothing is judged and everything says so

    @spec-0159 @e2e
    Scenario: With no judge pinned, a regeneration is refused
      Given the configuration pins no judge
      When a regeneration is ratified
      Then the gate refuses and says a regeneration needs a pinned judge

    @spec-0160 @e2e
    Scenario: The bootstrap build is ratified unjudged, and its record says so
      Given the configuration pins no judge
      When the bootstrap build is ratified
      Then the build's own gate runs it
      And the ratification is recorded as unjudged, naming the reviewer who accepted it

  # Decided by the event storm's hotspot H9 (dhf/storm/2026-10-24-rdm.md): unit
  # tests run in the gate and can refuse a build, but never verify a scenario.
  Rule: The gate runs the unit tests too

    @spec-0161 @e2e
    Scenario: A failing unit test refuses ratification, even when every scenario passed
      Given a build whose every scenario passes and one of whose unit tests fails
      When the gate ratifies it
      Then the gate refuses and names the failing unit test
