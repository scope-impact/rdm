# Component Wiki, container knowledge: dhf/spec/model.edn.
#
# The wiki sits between the raw sources and the spec: the domain, distilled,
# every claim footnoted to a source (skallinen/loyly README.md@17d68d2, "How to
# read the repo"; docs/wiki/index.md@17d68d2).

@UN-011
Feature: Wiki
  The domain wiki: every page indexed, every claim footnoted to a source.

  Rule: Every claim in the wiki cites a source

    @spec-0127 @e2e
    Scenario: A page with no footnote fails the check
      Given a wiki page with three paragraphs and no footnote
      When the wiki is checked
      Then the check fails and names the page

    @spec-0128 @e2e
    Scenario: A footnote whose citation does not resolve fails the check
      Given a wiki page whose footnote cites "loyly:docs/plan.md@17d68d2", which does not resolve
      When the wiki is checked
      Then the check fails and names the page, the footnote and why it did not resolve

    @spec-0129 @e2e
    Scenario: A page whose footnotes all resolve passes
      Given a wiki page whose every footnote cites a resolving source
      When the wiki is checked
      Then the page passes

  Rule: The wiki has one index, and every page is in it

    @spec-0130 @e2e
    Scenario: A page the index does not list fails the check
      Given the wiki page cut-and-fence.md and an index that does not link it
      When the wiki is checked
      Then the check fails and names the page

    @spec-0131 @e2e
    Scenario: An index entry with no page fails the check
      Given an index linking pages/scoring.md and no such page
      When the wiki is checked
      Then the check fails and names the entry

  # A wiki that names a stack has become a description of one implementation,
  # and a regeneration from it is no longer a measurement (skallinen/loyly
  # docs/wow/invariants.md@17d68d2, INV-2).
  Rule: The wiki never names an implementation

    @spec-0132 @e2e
    Scenario: A page naming a configured stack word fails the check
      Given the configured stack words include "Babashka"
      And a wiki page that says the gate is written in Babashka
      When the wiki is checked
      Then the check fails and names the page, the line and the word
