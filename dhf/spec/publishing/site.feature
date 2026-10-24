# Component Site, bounded context publishing of the rdm-spec container: dhf/spec/model.edn.
#
# The site is Markdown, built to HTML by the configured site builder, an
# external tool (dhf/harness/config.edn, :site): decided by the event storm's
# hotspot H6. rdm-spec writes the builder's configuration, never its code.

@UN-010
Feature: Site
  One page per component: its prose, its scenarios and their status.

  Rule: The site follows the model

    @spec-0092 @e2e
    Scenario: Each component gets one page
      Given a model with two bounded contexts holding five components
      When "rdm-spec publish" is run
      Then it holds five component pages, each named for its bounded context and component

    @spec-0093 @e2e
    Scenario: A component page carries the component's prose and scenarios
      Given the component Lint with design prose beside its feature file and fifteen scenarios
      When "rdm-spec publish" is run
      Then Lint's page holds its description, its prose and every scenario with its id, level and steps

    @spec-0094 @e2e
    Scenario: Each bounded context gets an index page with its component view
      Given the bounded context architecture and its rendered component view
      When "rdm-spec publish" is run
      Then the architecture page shows the view and links to each of its components' pages

    @spec-0095 @e2e
    Scenario: The home page shows the context and container views and the needs
      When "rdm-spec publish" is run
      Then the home page shows the context view, the container view and every user need with the scenarios tracing to it

  Rule: The site shows status, and never decides it

    @spec-0096 @contract
    Scenario: With results, each scenario shows its status
      Given Allure results in which spec-0012 passed and spec-0013 failed
      When "rdm-spec publish --results DIR" is run with that results directory as DIR
      Then spec-0012 shows verified and spec-0013 shows failed

    @spec-0097 @e2e
    Scenario: A planned scenario shows as planned
      Given the scenario @spec-0014 tagged @wip
      When "rdm-spec publish" is run
      Then spec-0014 shows planned

    @spec-0098 @e2e
    Scenario: Without results, the site says no status was derived
      Given the configured results directory does not exist
      When "rdm-spec publish" is run with no --results
      Then every page says no status was derived because no results were given

  Rule: The site is a reading of the spec, never a copy that can drift

    @spec-0099 @e2e
    Scenario: Publishing refuses views older than the model
      Given a rendered view whose recorded hash differs from the model's
      When "rdm-spec publish" is run
      Then it exits 1 and names the stale view

    @spec-0100 @e2e
    Scenario: Publishing writes nothing in the spec
      When "rdm-spec publish" is run
      Then no file under dhf/spec changes

    @spec-0101 @e2e
    Scenario: The same inputs publish the same site
      Given a spec, its views and a results directory
      When "rdm-spec publish" is run twice
      Then both sites' Markdown and site builder configuration are byte for byte the same

  Rule: The site publishes the domain wiki beside the spec

    @spec-0133 @e2e
    Scenario: Every wiki page is published with its footnotes linked to their sources
      Given a wiki page whose footnote cites "loyly:README.md@17d68d2"
      When "rdm-spec publish" is run
      Then the page is published and the footnote links to README.md at 17d68d2 in skallinen/loyly

  Rule: The site builder builds the site, from a configuration the model writes

    @spec-0146 @e2e
    Scenario: The site's navigation follows the model
      Given a model with the bounded contexts spec and architecture, in that order, each with its components
      When "rdm-spec publish" is run
      Then the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel
      And each section lists its context's page and then its components' pages, in the model's order

    @spec-0147 @e2e
    Scenario: The site builds under strict mode
      Given a published site whose pages all link to pages that exist
      When "rdm-spec publish" is run
      Then the configured site builder builds it, failing on any broken link, and the HTML is written

    @spec-0148 @e2e
    Scenario: A broken link fails the build, naming the page and the link
      Given a component page that links to a page the site does not hold
      When "rdm-spec publish" is run
      Then it exits 1 and names the page and the link

    # Incident: a gate once reported success because a missing tool turned its
    # command into a no-op that exited 0 (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
    @spec-0149 @e2e
    Scenario: Without the site builder, building fails and names the missing tool
      Given the configured site builder is not installed
      When "rdm-spec publish" is run
      Then it exits 2, names the missing site builder, and says no HTML was written
