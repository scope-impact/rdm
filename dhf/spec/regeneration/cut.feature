# Component Cut, bounded context regeneration of the rdm-spec container: dhf/spec/model.edn.

@UN-006
Feature: Cut
  Tags HEAD spec-N when it is origin/HEAD and pushes the tag; a regeneration
  reads nothing else.

  # At a fixed time the spec is cut, tagged and frozen, and a regeneration reads
  # that and nothing else (loyly:harness/README.md@17d68d2, "The idea
  # in five lines" 1).
  Rule: A cut is a pushed tag on the default branch, and regeneration reads only a cut

    @spec-0036 @e2e
    Scenario: HEAD on origin/HEAD is tagged spec-N and the tag is pushed
      Given HEAD is the commit origin/HEAD names, and the last cut is spec-6
      When "rdm-spec cut" is run
      Then HEAD is tagged spec-7
      And the tag is pushed to origin

  # Decided by the event storm's hotspot H2 (dhf/storm/2026-10-24-rdm.md): every
  # merge to the default branch is cut, by CI, so a cut never waits on anyone.
  Rule: Every merge to the default branch is cut, once

    @spec-0162 @e2e
    Scenario: Cutting a commit already cut changes nothing
      Given the commit tagged spec-7
      When "rdm-spec cut" is run again on it
      Then no tag is added and the cut says the commit is already spec-7

    @spec-0163 @e2e
    Scenario: A HEAD that is not origin/HEAD is refused
      Given HEAD is not the commit origin/HEAD names
      When "rdm-spec cut" is run on it
      Then it exits 2 and names the branch
