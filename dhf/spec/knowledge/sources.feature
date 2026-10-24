# Component Sources, container knowledge: dhf/spec/model.edn.

@UN-011
Feature: Sources
  Reads the provenance records and resolves citations against their pins.

  # The chain of citations has to bottom out in something no generation step
  # can quietly rewrite (skallinen/loyly docs/wow/invariants.md@17d68d2, INV-1).
  Rule: A source is indexed with its repository, its pins and its record

    @spec-0119 @e2e
    Scenario: A source is read with its repository, its pins and what it is evidence for
      Given the sources index names loyly with the repository skallinen/loyly, the pin 17d68d2 and the record loyly.md
      And loyly.md has an evidence table
      When the sources are read
      Then the source loyly is known with its repository, its pin 17d68d2 and its evidence rows

    @spec-0120 @e2e
    Scenario: A source with no pin fails the check
      Given the sources index names a source with a repository and no pin
      When the sources are read
      Then the check fails and names the source

    @spec-0121 @e2e
    Scenario: A source that drops a pin it had fails the check
      Given the sources index pinned loyly at 17d68d2 at the last commit
      When its pin is replaced by a newer one and 17d68d2 is no longer listed
      Then the check fails and names the source and the dropped pin

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

  # Decided by the event storm's hotspot H14 (dhf/storm/2026-10-24-rdm.md): a
  # source holding an implementation of what is regenerated hands the coding
  # agent the answer. What a source says may be cited; how it is built may not.
  Rule: A source's implementation is fenced

    @spec-0136 @e2e
    Scenario: A citation of a fenced path fails
      Given the sources index fences "harness/fence.sh" in loyly
      And a citation "loyly:harness/fence.sh@17d68d2"
      When citations are resolved
      Then the citation fails and names the fenced path and where it was cited

    @spec-0137 @e2e
    Scenario: A citation inside a fenced directory fails
      Given the sources index fences "rdm" in rdm
      And a citation "rdm:rdm/specification/sdd.py@3763e08"
      When citations are resolved
      Then the citation fails and names the fenced directory and where it was cited

    @spec-0138 @e2e
    Scenario: A fenced path that matches nothing at a pin fails the check
      Given a local checkout of skallinen/loyly at 17d68d2
      And the sources index fences "harness/missing.clj" in loyly
      When the sources are read
      Then the check fails and names the source, the pin and the path
