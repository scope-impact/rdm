# Component Site, container publishing: dhf/spec/model.edn.
#
# The site is Markdown, built to HTML by MkDocs: decided by the event storm's
# hotspot H6 (dhf/storm/2026-10-24-rdm.md). MkDocs is an external tool rdm-spec
# runs, like git and the renderer; rdm-spec writes its configuration and no
# Python.

@UN-010
Feature: Site
  One page per component: its prose, its scenarios and their status.

  Rule: The site follows the model

    @spec-0092 @e2e
    Scenario: Each component gets one page
      Given a model with two containers holding five components
      When the site is published
      Then it holds five component pages, each named for its container and component

    @spec-0093 @e2e
    Scenario: A component page carries the component's prose and scenarios
      Given the component Lint with design prose beside its feature file and fifteen scenarios
      When the site is published
      Then Lint's page holds its description, its prose and every scenario with its id, level and steps

    @spec-0094 @e2e
    Scenario: Each container gets an index page with its component view
      Given the container architecture and its rendered component view
      When the site is published
      Then the architecture page shows the view and links to each of its components' pages

    @spec-0095 @e2e
    Scenario: The home page shows the context and container views and the needs
      When the site is published
      Then the home page shows the context view, the container views and every user need with the scenarios tracing to it

  Rule: The site shows status, and never decides it

    @spec-0096 @contract
    Scenario: With results, each scenario shows its status
      Given Allure results in which spec-0012 passed and spec-0013 failed
      When the site is published with those results
      Then spec-0012 shows verified and spec-0013 shows failed

    @spec-0097 @e2e
    Scenario: A planned scenario shows as planned
      Given the scenario @spec-0014 tagged @wip
      When the site is published
      Then spec-0014 shows planned

    @spec-0098 @e2e
    Scenario: Without results, the site says no status was derived
      When the site is published with no results
      Then every page says no status was derived because no results were given

  Rule: The site is a reading of the spec, never a copy that can drift

    @spec-0099 @e2e
    Scenario: Publishing refuses views older than the model
      Given a rendered view whose recorded hash differs from the model's
      When the site is published
      Then publishing refuses and names the stale view

    @spec-0100 @e2e
    Scenario: Publishing writes nothing in the spec
      When the site is published
      Then no file under dhf/spec changes

    @spec-0101 @e2e
    Scenario: The same inputs publish the same site
      Given a spec, its views and a results directory
      When the site is published twice
      Then both sites are byte for byte the same

  Rule: The site publishes the domain wiki beside the spec

    @spec-0133 @e2e
    Scenario: Every wiki page is published with its footnotes linked to their sources
      Given a wiki page whose footnote cites "loyly:README.md@17d68d2"
      When the site is published
      Then the page is published and the footnote links to README.md at 17d68d2 in skallinen/loyly

  Rule: MkDocs builds the site, from a configuration the model writes

    @spec-0146 @e2e
    Scenario: The site's navigation follows the model
      Given a model with the containers spec and architecture, in that order, each with its components
      When the site is published
      Then the MkDocs configuration is written with the home page first, then a section for spec and one for architecture
      And each section lists its container's page and then its components' pages, in the model's order

    @spec-0147 @e2e
    Scenario: The site builds under strict mode
      Given a published site whose pages all link to pages that exist
      When the site is built
      Then MkDocs builds it in strict mode and the HTML is written

    @spec-0148 @e2e
    Scenario: A broken link fails the build, naming the page and the link
      Given a component page that links to a page the site does not hold
      When the site is built
      Then the build fails and names the page and the link

    # Incident: a gate once reported success because a missing tool turned its
    # command into a no-op that exited 0 (skallinen/loyly
    # harness/README.md@17d68d2, "The failure modes this shape was learned from").
    @spec-0149 @e2e
    Scenario: Without MkDocs, building fails and names the missing tool
      Given MkDocs is not installed
      When the site is built
      Then the build fails, names MkDocs, and says no HTML was written
