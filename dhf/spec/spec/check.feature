# Component Spec check, bounded context spec of the rdm-spec container: dhf/spec/model.edn.

@UN-002
Feature: Spec check
  The rules a spec must keep: one id and one level per scenario, only known tags, a
  need resting on a source, planned work marked, and no stack named.

  Rule: A scenario is the unit of requirement and carries exactly one id

    @spec-0042 @e2e
    Scenario: A scenario with two ids fails the spec check
      Given a scenario tagged both @spec-0007 and @spec-0008
      When "rdm-spec check" is run
      Then it fails and names the scenario and both ids

    @spec-0043 @e2e
    Scenario: An id used by two scenarios fails the spec check
      Given two scenarios both tagged @spec-0007
      When "rdm-spec check" is run
      Then it fails and names both files and the id

    @spec-0186 @e2e
    Scenario: A scenario with no id fails the spec check
      Given a scenario on line 30 of a feature file with no @spec- tag
      When "rdm-spec check" is run
      Then it fails, names the file and line 30, and says to run "rdm-spec stamp"

  Rule: A scenario carries exactly one verification level

    @spec-0044 @e2e
    Scenario Outline: Each level is accepted
      Given a stamped scenario tagged <level>
      When "rdm-spec check" is run
      Then the scenario passes the level check

      Examples:
        | level     |
        | @e2e      |
        | @contract |

    @spec-0045 @e2e
    Scenario: A scenario with no level fails the spec check
      Given a stamped scenario with no level tag
      When "rdm-spec check" is run
      Then it fails and names the scenario's id and the two levels

    @spec-0046 @e2e
    Scenario: A scenario with two levels fails the spec check
      Given a stamped scenario tagged both @e2e and @contract
      When "rdm-spec check" is run
      Then it fails and names the scenario's id and both levels

    # Decided by the event storm's hotspot H4 (dhf/storm/2026-10-24-rdm.md):
    # there is no persona level.
    @spec-0150 @e2e
    Scenario: A tag that is not an id, a level, a need or @wip fails the spec check
      Given a stamped scenario tagged @persona
      When "rdm-spec check" is run
      Then it fails, names the scenario's id and @persona, and lists the tags a scenario may carry

  Rule: Every scenario traces to a user need

    @spec-0047 @e2e
    Scenario: A need tag on the feature is inherited by every scenario under it
      Given a feature tagged @UN-003
      And a scenario under it carries no need tag of its own
      When "rdm-spec trace UN-003" is run
      Then it lists the scenario

    @spec-0048 @e2e
    Scenario: A scenario that traces to no need fails the spec check
      Given a stamped scenario with no need tag on it, its rule or its feature
      When "rdm-spec check" is run
      Then it fails and names the scenario's id

    @spec-0049 @e2e
    Scenario: A need tag naming no declared need fails the spec check
      Given the declared needs are UN-001 to UN-005
      And a scenario tagged @UN-009
      When "rdm-spec check" is run
      Then it fails and names UN-009 and the scenario's id

  Rule: Every user need rests on a source

    @spec-0134 @e2e
    Scenario: A user need citing no source fails the spec check
      Given the need UN-004 with no sources
      When "rdm-spec check" is run
      Then it fails and names UN-004

    @spec-0135 @e2e
    Scenario: A user need citing a source that does not resolve fails the spec check
      Given the recorded source demo, pinned at the commit of a checkout given with --sources
      And the need UN-004 citing "demo:docs/nothing.md" at that pin, a path the checkout does not hold
      When "rdm-spec check --sources DIR" is run
      Then it fails and names UN-004, the citation and why it did not resolve

    @spec-0187 @e2e
    Scenario: A citation that could not be checked passes the spec check with a warning
      Given the need UN-004 citing "loyly:README.md@17d68d2"
      And no checkout of loyly is given
      When "rdm-spec check" is run
      Then it passes
      And it warns that the citation was not checked, and why

  Rule: Planned work is a scenario tagged @wip

    @spec-0050 @e2e
    Scenario: The spec check accepts a scenario tagged @wip
      Given a spec whose one stamped scenario is tagged @wip
      When "rdm-spec check" is run
      Then it passes

  # The spec never names its stack: a spec that names one has become a
  # description of one implementation, and regenerating from it measures
  # nothing (loyly:docs/wow/invariants.md@17d68d2, INV-2). The stack lives in
  # dhf/harness/stack.md.
  Rule: The spec never names an implementation

    @spec-0209 @e2e
    Scenario: A spec file naming a configured stack word fails the spec check
      Given the configured stack words include "Cobalt"
      And a feature file whose description says the parser is written in Cobalt
      When "rdm-spec check" is run
      Then it fails and names the file, the line and the word
