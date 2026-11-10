Feature: Validation visible at release
  A reviewer can see, at release, which user needs have no approved validation by a person.

  @item-01-004
  Scenario: RDM shall ingest validation records (user need, disposition, reviewer) from the DHF's validation directory and report, at the release gate, each user need of the spec lacking an approved validation record (an approved disposition with a named reviewer) as a warning that does not block release.
