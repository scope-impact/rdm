Feature: Compile the DHF
  A regulatory author can compile a design history file from the system of record: the spec, the executed tests and git.

  @item-01-001
  Scenario: RDM shall read the spec's requirements from Gherkin: each scenario tagged with an id (any id-shaped tag, or the pattern the spec's configuration names) a requirement, with its Feature, Rule, title, source line and tags, the last id tag before a scenario its id, and each untagged scenario listed; finding the feature files by the configuration's globs, else under spec/ or features/ at the repository root, or through a layout adapter (Löyly's plan directory: planned and backlog slices skipped, drafts.feature never read); with no project-management dependency.
