# Kernel component Event bus: dhf/spec/model.edn, :kernel.
#
# Decided by the event storm's hotspot H11 (dhf/storm/2026-10-24-rdm.md):
# containers meet only on an in-process bus. Delivery is synchronous so that a
# run is deterministic: the same inputs publish the same events in the same
# order, which the site's byte-for-byte rule depends on.

@UN-005
Feature: Event bus
  Delivers each catalogued event to its subscribers, in process.

  Rule: The bus delivers, in order, and returns only when every subscriber is done

    @spec-0112 @e2e
    Scenario: An event reaches every subscriber, in the order they subscribed
      Given publishing and measurement both subscribe to VerificationDerived, publishing first
      When verification publishes VerificationDerived
      Then publishing handles it, and then measurement does

    @spec-0113 @e2e
    Scenario: Publishing returns after every subscriber has handled the event
      Given a subscriber to SpecParsed that records when it ran
      When spec publishes SpecParsed
      Then the record exists by the time publishing returns

    @spec-0114 @e2e
    Scenario: An event with no subscriber is delivered to no one, and the run goes on
      Given nothing subscribes to CycleScored
      When measurement publishes CycleScored
      Then the run continues

  # A failure the bus swallowed would look exactly like a subscriber that
  # agreed. Silence must never read as a pass (skallinen/loyly
  # harness/README.md@17d68d2, "What makes the checks run").
  Rule: The bus refuses what the catalogue does not declare, and never swallows a failure

    @spec-0115 @contract
    Scenario: An event the catalogue does not declare is refused
      Given the event SpecArchived is not in the event catalogue
      When a container publishes SpecArchived
      Then the bus refuses it and names the event and the publisher

    @spec-0116 @contract
    Scenario: An event missing a key it carries is refused
      Given the catalogue says SpecCut carries a tag and a commit
      When regeneration publishes SpecCut with a tag and no commit
      Then the bus refuses it and names the event and the missing key

    @spec-0117 @contract
    Scenario: Only the declared publisher may publish an event
      Given the catalogue says SpecCut is published by regeneration
      When measurement publishes SpecCut
      Then the bus refuses it and names the event, the declared publisher and the one that tried

    @spec-0118 @e2e
    Scenario: A subscriber that fails fails the publish
      Given a subscriber to SpecGated that throws
      When spec publishes SpecGated
      Then publishing the event fails and names the event, the subscriber and its error
