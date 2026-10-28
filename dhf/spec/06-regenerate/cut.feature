@UN-006
Feature: Cut
  Tags HEAD spec-N when it is origin/HEAD and pushes the tag; a regeneration
  reads nothing else.

  Rule: A cut is a pushed tag on the default branch, and regeneration reads only a cut

    @spec-0036 @e2e
    Scenario: HEAD on origin/HEAD is tagged spec-N and the tag is pushed
      Given HEAD is the commit origin/HEAD names, and the last cut is spec-6
      When "rdm-spec cut" is run
      Then HEAD is tagged spec-7
      And the tag is pushed to origin

  Rule: Every merge to the default branch is cut, once

    @spec-0162 @e2e
    Scenario: Cutting a commit already cut changes nothing
      Given the commit tagged spec-7
      When "rdm-spec cut" is run again on it
      Then no tag is added and the cut says the commit is already spec-7

    @spec-0224 @e2e
    Scenario: A HEAD whose spec fails the check is not cut
      Given HEAD is the commit origin/HEAD names, and "rdm-spec check" exits 1 on it
      When "rdm-spec cut" is run
      Then it exits 1, tags nothing, and prints each of the check's finding lines unchanged

    @spec-0163 @e2e
    Scenario: A HEAD that is not origin/HEAD is refused
      Given HEAD is not the commit origin/HEAD names
      When "rdm-spec cut" is run on it
      Then it exits 2 and names the branch
