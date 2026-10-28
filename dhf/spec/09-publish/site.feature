@UN-010
Feature: Site
  One page per component: its prose, its scenarios and their status.

  Rule: The site follows the model

    @spec-0092 @e2e
    Scenario: Each component gets one page
      Given a model with two bounded contexts holding five components, and no kernel
      When "rdm-spec publish" is run
      Then it holds five component pages, each named for its bounded context and component

    @spec-0093 @e2e
    Scenario: A component page carries the component's prose and scenarios
      Given the component Lint with design prose beside its feature file and its scenarios
      When "rdm-spec publish" is run
      Then Lint's page holds its description from the model, its prose and every scenario with its id, level and steps

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
      Given a copy of rdm-spec's own project in which the configured results directory does not exist
      When "rdm-spec publish" is run with no --results
      Then every page, the wiki's included, says no status was derived because no results were given

  Rule: The site is a reading of the spec, never a copy that can drift

    @spec-0099 @e2e
    Scenario: Publishing refuses views older than the model
      Given a rendered view whose recorded hash differs from the model's
      When "rdm-spec publish" is run
      Then it exits 1 and names the stale view

    @spec-0100 @e2e
    Scenario: Publishing writes nothing in the spec
      Given a project set up by rdm-spec, its spec as it is before the publish kept aside
      When "rdm-spec publish" is run
      Then it passes
      And no file in the spec changes

    @spec-0101 @e2e
    Scenario: The same inputs publish the same site
      Given a spec, its views and a results directory
      When "rdm-spec publish" is run twice, the first site kept aside
      Then both sites' Markdown and site builder configuration are byte for byte the same
      And no page names a path outside the project

  Rule: The site publishes the domain wiki beside the spec

    @spec-0133 @e2e
    Scenario: Every wiki page is published with its footnotes linked to their sources
      Given a wiki page whose footnote cites "loyly:README.md@17d68d2"
      When "rdm-spec publish" is run
      Then the page is published and the footnote links to README.md at 17d68d2 in skallinen/loyly

    @spec-0273 @e2e
    Scenario: The wiki's index is published with its footnotes linked, as a page is
      Given the wiki's index holds a footnote citing "loyly:README.md@17d68d2"
      When "rdm-spec publish" is run
      Then the published index's footnote links to README.md at 17d68d2 in skallinen/loyly

    @spec-0239 @e2e
    Scenario: The wiki is reachable from the home page and the navigation
      Given a wiki whose index lists two pages
      When "rdm-spec publish" is run
      Then the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel

  Rule: The site builder builds the site, from a configuration the model writes

    @spec-0146 @e2e
    Scenario: The site's navigation follows the model
      Given a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component
      When "rdm-spec publish" is run
      Then the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel
      And each section lists its context's page and then its components' pages, in the model's order

    @spec-0147 @e2e
    Scenario: The site builds when every link resolves
      Given a published site whose pages all link to pages that exist
      When "rdm-spec publish" is run
      Then the configured site builder builds it, failing on any broken link, and the HTML is written

    @spec-0148 @e2e
    Scenario: A broken link fails the build, naming the page and the link
      Given a component page that links to a page the site does not hold
      When "rdm-spec publish" is run
      Then it exits 1 and names the page and the link
      And no HTML is left in the site directory

    @spec-0149 @e2e
    Scenario: Without the site builder, building fails and names the missing tool
      Given the configured site builder is not installed, and a site directory left by an earlier publish
      When "rdm-spec publish" is run
      Then it exits 2, names the missing site builder, and says no HTML was written
      And no HTML is left in the site directory
