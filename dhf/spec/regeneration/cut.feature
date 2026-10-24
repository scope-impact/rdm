# Component Cut, container regeneration: dhf/spec/model.edn.

@UN-006
Feature: Cut
  Tags a merged spec change; a regeneration reads nothing else.

  # At a fixed time the spec is cut, tagged and frozen, and a regeneration reads
  # that and nothing else (skallinen/loyly harness/README.md@17d68d2, "The idea
  # in five lines" 1).
  Rule: A cut is a tagged merge, and regeneration reads only a cut

    @spec-0036 @e2e
    Scenario: The cut is a tag on a commit of the default branch
      Given the merge commit of a spec change on the default branch
      When "rdm-spec cut" is run
      Then the commit is tagged spec-N, with N one more than the last cut

  # Decided by the event storm's hotspot H2 (dhf/storm/2026-10-24-rdm.md): every
  # merge to the default branch is cut, by CI, so a cut never waits on anyone.
  Rule: Every merge to the default branch is cut, once

    @spec-0162 @e2e
    Scenario: Cutting a commit already cut changes nothing
      Given the commit tagged spec-7
      When "rdm-spec cut" is run again on it
      Then no tag is added and the cut says the commit is already spec-7

    @spec-0163 @e2e
    Scenario: A commit off the default branch is refused
      Given a commit on a branch other than the default branch
      When "rdm-spec cut" is run on it
      Then it exits 2 and names the branch
