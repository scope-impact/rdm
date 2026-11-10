Feature: Records
  The document control system's requirements in this context: each stamped scenario is
  one requirement, verified by the acceptance test that claims it.

  @allium-guarantee.CurrentRevision
  @item-02-001
  Scenario: Every controlled document shall declare its identity and revision in frontmatter.

  @allium-guarantee.CurrentRevision
  @item-02-002
  Scenario: Each rendered controlled document shall embed its revision history from repository data, not a hand-maintained table.

  @allium-guarantee.Part11Controls
  @item-02-003
  Scenario: The document control procedure shall address every item of the Part 11 document-control checklist, with the gap analysis reporting full coverage.

  @allium-guarantee.DeviceRecords
  @item-02-004
  Scenario: The device master record shall be a controlled index document enumerating the specification set, rendered from index data that is generated from the controlled documents' own frontmatter, so it lists each controlled document with its identity and revision.
