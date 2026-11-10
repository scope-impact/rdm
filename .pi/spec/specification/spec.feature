Feature: Specification
  RDM's own requirements in this context: each stamped scenario is one requirement,
  verified by the acceptance tests that claim it with @allure.tag("item-NN-SSS").

  @allium-guarantee.CompileDhf @allium-guarantee.TraceVerification
  @item-01-001
  Scenario: RDM shall read the requirements of a Löyly-shaped spec as Löyly's checklist reads them: the plan entries whose name starts with a digit, in order, skipping a slice whose plan says it is planned or backlog; each stamped scenario of its spec.feature (never drafts.feature) a requirement item-NN-SSS, with its Rule, title, source line and the Allium obligations its tags name; each unstamped scenario listed; with no project-management dependency.

  @allium-guarantee.ScaffoldProject
  @item-01-002
  Scenario: RDM shall scaffold a new documentation project from one command into a directory that does not yet exist, laying down the document templates (the traceability matrix among them), build Makefile, container build and render config, buildable offline without an RDM checkout, and naming what it laid down and the next steps.

  @allium-guarantee.AdoptRepository
  @item-01-003
  Scenario: RDM shall bring an existing repository under RDM from one command: lay down the DHF skeleton (V&V plan, traceability matrix, render configuration, README), a CI gate workflow and a .gitignore of what RDM generates, skipping (never overwriting) any destination that already exists, a symbolic link included, and naming the .gitignore lines to add when it keeps an existing one.

  @allium-guarantee.ValidationVisible
  @item-01-004
  Scenario: RDM shall ingest validation records (user need, disposition, reviewer) from the DHF's validation directory and report, at the release gate, each user need of the spec lacking an approved validation record (an approved disposition with a named reviewer) as a warning that does not block release.

  @allium-guarantee.TraceVerification
  @item-01-005
  Scenario: RDM shall read the user needs of the spec: each screen of its event model with the Allium surfaces it links (the actors they face, the rules they provide, what they guarantee) and the event-storm events its commands produce, or, in a context with no screens, each surface with its doc comment; a requirement addresses each need whose rules or guarantees it covers, else the needs of its own context.
