# Component Status, bounded context verification of the rdm-spec container: dhf/spec/model.edn.
# Slice 13-slice-development (ADR 0005).

@UN-003
Feature: Status
  Reports where each slice stands.

  # spec-33, ADR 0005: the slice is the unit of work (Löyly,
  # loyly:docs/wow/way-of-working.md@17d68d2, sections 1, 2 and 6).
  Rule: Status reports each slice

    @spec-0419 @e2e
    Scenario: Status reports each slice's plan status and its scenarios verified of all
      Given a configuration with :spec-layout :slices; the slice 11-project-in-a-folder, "status: building", with 5 stamped scenarios of which results R verify 3; and the slice 12-several-specs, "status: done", with every scenario verified
      When "rdm-spec status --results R" is run
      Then its result holds :slices [{:slice "11-project-in-a-folder" :status "building" :verified 3 :scenarios 5} {:slice "12-several-specs" :status "done" :verified N :scenarios N}], in the order of their numbers, beside every scenario's status
      And "rdm-spec status --results R --slice 11-project-in-a-folder" reports that slice and its scenarios only
