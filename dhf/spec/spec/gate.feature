# Component Spec gate, container spec: dhf/spec/model.edn.

@UN-002
Feature: Spec gate
  The rules every scenario in the spec must keep: one id, one level, a known need.

  Rule: A scenario is the unit of requirement and carries exactly one id

    @spec-0042 @e2e
    Scenario: A scenario with two ids fails the gate
      Given a scenario tagged both @spec-0007 and @spec-0008
      When the gate runs
      Then it fails and names the scenario and both ids

    @spec-0043 @e2e
    Scenario: An id used by two scenarios fails the gate
      Given two scenarios both tagged @spec-0007
      When the gate runs
      Then it fails and names both files and the id

  Rule: A scenario carries exactly one verification level

    @spec-0044 @e2e
    Scenario Outline: Each level is accepted
      Given a stamped scenario tagged <level>
      When the gate runs
      Then the scenario passes the level check

      Examples:
        | level     |
        | @e2e      |
        | @contract |
        | @example  |
        | @property |

    @spec-0045 @e2e
    Scenario: A scenario with no level fails the gate
      Given a stamped scenario with no level tag
      When the gate runs
      Then it fails and names the scenario's id and the four levels

    @spec-0046 @e2e
    Scenario: A scenario with two levels fails the gate
      Given a stamped scenario tagged both @e2e and @contract
      When the gate runs
      Then it fails and names the scenario's id and both levels

    # Decided by the event storm's hotspot H4 (dhf/storm/2026-10-24-rdm.md):
    # there is no persona level.
    @spec-0150 @e2e
    Scenario: A scenario tagged with a level that is not one of the four fails the gate
      Given a stamped scenario tagged @persona
      When the gate runs
      Then it fails and names the scenario's id, @persona and the four levels

  Rule: Every scenario traces to a user need

    @spec-0047 @e2e
    Scenario: A need tag on the feature is inherited by every scenario under it
      Given a feature tagged @UN-003
      And a scenario under it carries no need tag of its own
      When the gate runs
      Then the scenario traces to UN-003

    @spec-0048 @e2e
    Scenario: A scenario that traces to no need fails the gate
      Given a stamped scenario with no need tag on it, its rule or its feature
      When the gate runs
      Then it fails and names the scenario's id

    @spec-0049 @e2e
    Scenario: A need tag naming no declared need fails the gate
      Given the declared needs are UN-001 to UN-005
      And a scenario tagged @UN-009
      When the gate runs
      Then it fails and names UN-009 and the scenario's id

  Rule: Every user need rests on a source

    @spec-0134 @e2e
    Scenario: A user need citing no source fails the gate
      Given the need UN-004 with no sources
      When the gate runs
      Then it fails and names UN-004

    @spec-0135 @e2e
    Scenario: A user need citing a source that does not resolve fails the gate
      Given the need UN-004 citing "innolitics-rdm:docs/nothing.md@f21b508", which the sources report as not resolving
      When the gate runs
      Then it fails and names UN-004, the citation and why it did not resolve

  Rule: Planned work is a scenario tagged @wip

    @spec-0050 @e2e
    Scenario: The gate accepts a scenario tagged @wip
      Given a stamped scenario tagged @wip with no passing result
      When the gate runs
      Then the gate passes
