# Component Release check, bounded context verification of the rdm-spec container: dhf/spec/model.edn.
# Slice 10-regulated-profile: the release check's regulated rule (ADR 0004).

@UN-013
Feature: Release check
  Under the regulated profile, a release needs every declared clause named.

  # A clause no document names is unverified the way a scenario with no result
  # is (dhf/spec/10-regulated-profile/gap.feature).
  @UN-013
  Rule: Under the regulated profile, a release needs every clause named

    @spec-0267 @e2e
    Scenario: The release check refuses a clause no audit tag names
      Given a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names
      When "rdm-spec release" is run
      Then it exits 1 and names the clause and its clause list

    @spec-0278 @e2e
    Scenario: The release check refuses a controlled document with no id
      Given a regulated project whose every stamped scenario has a passing result and every clause is named, and a document with no id in its frontmatter
      When "rdm-spec release" is run
      Then it exits 1 and names the document
