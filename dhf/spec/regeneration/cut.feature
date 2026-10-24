# Component Cut, container regeneration: dhf/spec/model.edn.

@UN-006
Feature: Cut
  Tags a merged spec change; a regeneration reads nothing else.

  # Incident: a builder that can read last cycle's scores can aim at the
  # checklist instead of the spec. Regeneration reads a cut, nothing else.
  Rule: A cut is a tagged merge, and regeneration reads only a cut

    @spec-0036 @e2e
    Scenario: The cut is a tag on a commit of the default branch
      Given the merge commit of a spec change on the default branch
      When the cut is made
      Then the commit is tagged spec-N, with N one more than the last cut
