# Kernel component Event bus: dhf/spec/model.edn, :kernel.
#
# Decided by the event storm's hotspot H11 (dhf/storm/2026-10-24-rdm.md):
# bounded contexts meet only on an in-process bus, within one run. How it delivers is design (ADR
# 0002, "Events"); what a black box can see of it is that every run can show
# its events, and the same inputs always show the same ones.

@UN-005
Feature: Event bus
  Delivers each catalogued event to its subscribers, in process.

  Rule: A run can show the events it published, and they never vary

    @spec-0166 @e2e
    Scenario: A task run with --events prints each event it published, in order
      When "rdm-spec check --events" is run
      Then the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published

    @spec-0167 @e2e
    Scenario: The same inputs publish the same events in the same order
      Given a spec and its sources unchanged between two runs
      When "rdm-spec check --events" is run twice
      Then both runs list the same events in the same order

  # rdm-spec is the harness, and a project is what it is used on. Its own
  # events, those between its contexts, are part of rdm-spec: a release
  # carries them, built from rdm-spec's own spec. A project's catalogue holds
  # the project's events, its design record, and never what the bus carries:
  # a project that declared one event of its own had its check refused until
  # it copied rdm-spec's five, and an empty catalogue left rdm-spec's
  # contracts unchecked (spec owner, cycle 14; it replaces spec-0221, cycle
  # 00, A5, whose bus read the project's catalogue).
  Rule: The bus carries rdm-spec's own events, whatever a project's catalogue declares

    @spec-0303 @e2e
    Scenario: A project whose catalogue declares only its own events passes check
      Given a project set up by rdm-spec whose configured event catalogue declares only the event OrderPlaced, published by its own component
      When "rdm-spec check --events" is run
      Then it exits 0 and lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher

    @spec-0304 @e2e
    Scenario Outline: A project's catalogue never changes the events a run publishes
      Given a project set up by rdm-spec whose configured event catalogue is <catalogue>
      When "rdm-spec check --events" is run
      Then it lists the same events, with the same publishers, in the same order, as with an empty catalogue

      Examples:
        | catalogue                                                       |
        | empty                                                           |
        | one that declares only its own event OrderPlaced                |
        | one that declares SpecParsed with another publisher             |
