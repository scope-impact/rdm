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

  # One catalogue: the project's, so the bus and the lint can never disagree
  # (cycle 00, dhf/cycles/00/proposed-spec-changes.md, A5). An empty
  # catalogue, as init writes, declares nothing and so checks nothing
  # (cycle 01, A2).
  Rule: The bus carries only the events the project's catalogue declares

    @spec-0221 @e2e
    Scenario: Publishing an event the catalogue does not declare is refused
      Given the configured event catalogue declares events, but not SpecParsed
      When "rdm-spec check" is run
      Then it exits 2 and names SpecParsed and the configured catalogue
