# Component Sources, container knowledge: dhf/spec/model.edn.

@UN-011
Feature: Sources
  Reads the provenance records and resolves citations against their pins.

  # The chain of citations has to bottom out in something no generation step
  # can quietly rewrite (skallinen/loyly docs/wow/invariants.md@17d68d2, INV-1).
  Rule: A source is a provenance record with a pin

    @spec-0119 @e2e
    Scenario: A record is read with its repository, its pins and what it is evidence for
      Given the record loyly.md naming skallinen/loyly, pinned at 17d68d2, with an evidence table
      When the sources are read
      Then the source loyly is known with its repository, its pin 17d68d2 and its evidence rows

    @spec-0120 @e2e
    Scenario: A record with no pin fails the check
      Given a record naming a repository and no pinned commit
      When the sources are read
      Then the check fails and names the record

    @spec-0121 @e2e
    Scenario: A record that drops a pin it had fails the check
      Given the record loyly.md was pinned at 17d68d2 at the last commit
      When its pin is replaced by a newer one and 17d68d2 is no longer listed
      Then the check fails and names the record and the dropped pin

  Rule: A citation resolves to a source, a pin and a path

    @spec-0122 @e2e
    Scenario: A citation naming no recorded source fails
      Given a citation "innolitix:README.md@f21b508" and no source innolitix
      When citations are resolved
      Then the citation fails and names innolitix and where it was cited

    @spec-0123 @e2e
    Scenario: A citation at a commit that is not one of its source's pins fails
      Given the source loyly is pinned only at 17d68d2
      And a citation "loyly:README.md@0000000"
      When citations are resolved
      Then the citation fails and names the commit and the source's pins

    @spec-0124 @e2e
    Scenario: A citation resolves when its path exists at its pin
      Given a local checkout of skallinen/loyly holding README.md at 17d68d2
      When the citation "loyly:README.md@17d68d2" is resolved
      Then it resolves

    @spec-0125 @e2e
    Scenario: A path that does not exist at its pin fails
      Given a local checkout of skallinen/loyly with no file docs/plan.md at 17d68d2
      When the citation "loyly:docs/plan.md@17d68d2" is resolved
      Then the citation fails and names the path and the pin

    @spec-0126 @e2e
    Scenario: Without a checkout, a citation's path is reported unchecked, never passed
      Given no local checkout of skallinen/loyly is configured
      When the citation "loyly:README.md@17d68d2" is resolved
      Then it is reported as unchecked, with the reason
