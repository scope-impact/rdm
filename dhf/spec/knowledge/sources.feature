# Component Sources, bounded context knowledge of the rdm-spec container: dhf/spec/model.edn.

@UN-011
Feature: Sources
  Reads the provenance records and resolves citations against their pins.

  # The chain of citations has to bottom out in something no generation step
  # can quietly rewrite (loyly:docs/wow/invariants.md@17d68d2, INV-1).
  Rule: A source is indexed with its repository, its pins and its record

    @spec-0120 @e2e
    Scenario: A source with no pin fails the check
      Given the sources index names a source with a repository and no pin
      When "rdm-spec check" is run
      Then the check fails and names the source

    @spec-0121 @e2e
    Scenario: A source that drops a pin it had fails the check
      Given a commit of the sources index pins loyly at 17d68d2, and a later commit no longer lists it
      When "rdm-spec check" is run
      Then the check fails and names the source, the dropped pin and the commit that dropped it

    # A source removed on purpose once read as dropped pins (cycle 04).
    @spec-0236 @e2e
    Scenario: A source is retired, never deleted, and nothing may cite it
      Given a commit of the sources index lists rdm with its pins, and a later commit keeps them and adds :retired "holds today's implementation"
      And a wiki page whose footnote cites "rdm:README.md@3763e08"
      When "rdm-spec check" is run
      Then the check reports no dropped pin for rdm
      And it fails and names the citation and that its source is retired

    @spec-0240 @e2e
    Scenario: A need's source that is not a citation fails the check
      Given the need UN-004 whose sources hold "the loyly README"
      When "rdm-spec check" is run
      Then it fails rule citation-malformed and names UN-004 and the source

  # Checkouts are machine-local, so they are never committed: they are given on
  # the command line, a directory holding one checkout per source, named by
  # the source's id.
  Rule: A citation resolves to a source, a pin and a path

    @spec-0171 @contract
    Scenario: Source checkouts are given as one directory, one checkout per source
      Given a directory holding loyly/ and innolitics-rdm/, checkouts of those sources
      When "rdm-spec check --sources DIR" is run with that directory as DIR
      Then citations of loyly and innolitics-rdm are checked against those checkouts

    @spec-0122 @e2e
    Scenario: A citation naming no recorded source fails
      Given a citation "innolitix:README.md@f21b508" and no source innolitix
      When "rdm-spec check" is run
      Then the citation fails and names innolitix and where it was cited

    @spec-0123 @e2e
    Scenario: A citation at a commit that is not one of its source's pins fails
      Given the source loyly is pinned only at 17d68d2
      And a citation "loyly:README.md@0000000"
      When "rdm-spec check" is run
      Then the citation fails and names the commit and the source's pins

    @spec-0124 @e2e
    Scenario: A citation resolves when its path exists at its pin
      Given a checkout of skallinen/loyly, holding README.md at 17d68d2, given with --sources
      When "rdm-spec check --sources DIR" resolves the citation "loyly:README.md@17d68d2"
      Then it resolves

    @spec-0125 @e2e
    Scenario: A path that does not exist at its pin fails
      Given a checkout of skallinen/loyly, with no file docs/plan.md at 17d68d2, given with --sources
      When "rdm-spec check --sources DIR" resolves the citation "loyly:docs/plan.md@17d68d2"
      Then the citation fails and names the path and the pin

    @spec-0126 @e2e
    Scenario: Without a checkout, a citation's path is reported unchecked, never passed
      Given no checkout of skallinen/loyly is given with --sources
      When "rdm-spec check" resolves the citation "loyly:README.md@17d68d2"
      Then it is reported as unchecked, with the reason

  # Decided by the event storm's hotspot H14 (dhf/storm/2026-10-24-rdm.md): a
  # source holding an implementation of what is regenerated hands the builder
  # the answer. What a source says may be cited; how it is built may not.
  Rule: A source's implementation is fenced

    @spec-0136 @e2e
    Scenario: A citation of a fenced path fails
      Given the sources index fences "harness/fence.sh" in loyly
      And a citation "loyly:harness/fence.sh@17d68d2"
      When "rdm-spec check" is run
      Then the citation fails and names the fenced path and where it was cited

    @spec-0137 @e2e
    Scenario: A citation inside a fenced directory fails
      Given the sources index fences "rdm" in innolitics-rdm
      And a citation "innolitics-rdm:rdm/render.py@f21b508"
      When "rdm-spec check" is run
      Then the citation fails and names the fenced directory and where it was cited

    @spec-0138 @e2e
    Scenario: A fenced path that matches nothing at a pin fails the check
      Given a checkout of skallinen/loyly at 17d68d2, given with --sources
      And the sources index fences "harness/missing.clj" in loyly
      When "rdm-spec check --sources DIR" is run
      Then the check fails and names the source, the pin and the path

  Rule: Citations are read from the needs and the wiki, nothing else

    @spec-0199 @e2e
    Scenario: Only needs.edn and the wiki's pages are scanned for citations
      Given a feature file whose example step quotes a citation of a fenced path
      When "rdm-spec check" is run
      Then the quoted citation is not resolved, and the check passes

  # A need's citations are resolved here, with the wiki's, so one component
  # reports them (cycle 01, C20). The spec check keeps only "a need cites no
  # source".
  Rule: A need's citations resolve

    @spec-0135 @e2e
    Scenario: A user need citing a source that does not resolve fails the check
      Given the recorded source demo, pinned at the commit of a checkout given with --sources
      And the need UN-004 citing "demo:docs/nothing.md" at that pin, a path the checkout does not hold
      When "rdm-spec check --sources DIR" is run
      Then it fails and names UN-004, the citation and why it did not resolve

    @spec-0187 @e2e
    Scenario: A citation that could not be checked passes the check with a warning
      Given the need UN-004 citing "loyly:README.md@17d68d2"
      And no checkout of loyly is given
      When "rdm-spec check" is run
      Then it passes
      And it warns that the citation was not checked, and why
