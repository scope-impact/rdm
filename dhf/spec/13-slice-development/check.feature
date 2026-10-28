@UN-001 @UN-002
Feature: Spec check
  Holds every slice to its plan.

  @UN-001
  Rule: Every slice is planned

    @spec-0400 @e2e
    Scenario: A slice's plan says its goal, its scope and what it rests on
      Given a configuration with :spec-layout :slices, and dhf/spec/12-several-specs/plan.md with the headings "Goal", "References", "Scope" and "Decisions", whose References name UN-001 and whose Scope has "In" and "Out"
      When "rdm-spec check" is run
      Then it exits 0 with no finding about the slice

    @spec-0401 @e2e
    Scenario Outline: A slice that is not planned is a finding
      Given a configuration with :spec-layout :slices, and a slice 12-several-specs whose <plan>
      When "rdm-spec check" is run
      Then it exits 1 with the finding <rule>, naming dhf/spec/12-several-specs/plan.md

      Examples:
        | plan                                                   | rule                   |
        | folder holds no plan.md                                | slice-unplanned        |
        | plan.md has no "Goal" heading                          | slice-unplanned        |
        | plan.md's References name no user need                 | slice-unfounded        |
        | plan.md's References name UN-099, which no need is     | slice-unfounded        |

  Rule: A slice says where it stands, and what was decided in it

    @spec-0411 @e2e
    Scenario Outline: A slice's status is one of four, under its plan's title
      Given a configuration with :spec-layout :slices, and dhf/spec/12-several-specs/plan.md whose line under its title is <line>
      When "rdm-spec check" is run
      Then it <answers>

      Examples:
        | line                | answers                                                                              |
        | "status: planned"   | exits 0                                                                              |
        | "status: ready"     | exits 0                                                                              |
        | "status: building"  | exits 0                                                                              |
        | "status: done"      | exits 0                                                                              |
        | "status: finished"  | exits 1 with the finding slice-status, naming the plan and the four statuses        |
        | no status line      | exits 1 with the finding slice-status, naming the plan                               |

    @spec-0412 @e2e
    Scenario: A slice holding planned work is not done
      Given a configuration with :spec-layout :slices, and a slice whose plan says "status: done" and one of whose scenarios is tagged @wip
      When "rdm-spec check" is run
      Then it exits 1 with the finding slice-done-planned, naming the plan and the planned scenario

    @spec-0413 @e2e
    Scenario: Every slice keeps its decisions
      Given a configuration with :spec-layout :slices, and a slice with a plan and no decisions.md
      When "rdm-spec check" is run
      Then it exits 1 with the finding slice-undecided, naming the slice's folder
