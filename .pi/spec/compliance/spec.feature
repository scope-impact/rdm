Feature: Compliance
  RDM's own requirements in this context: each stamped scenario is one requirement,
  verified by the acceptance tests that claim it with @allure.tag("item-NN-SSS").

  @allium-guarantee.GapAnalysis
  @item-05-001
  Scenario: RDM shall report the checklist references (keys) absent from a set of documents, exiting non-zero when any required reference is missing or nothing could be checked, warning of a document that still holds a placeholder, and ending quietly when its reader stops.

  @allium-guarantee.GapAnalysis
  @item-05-002
  Scenario: RDM shall ship composable built-in checklists for the applicable standards (IEC 62304, ISO 14971, FDA-SW/CYBER/HFE), each with clauses under unique keys, resolving `include` directives between them by built-in name.

  @allium-guarantee.GapAnalysis
  @item-05-003
  Scenario: RDM shall report coverage of documents against a checklist (total / missing / covered / percent), listing the missing items in verbose mode, and exiting as the gap report does.

  @allium-guarantee.GapAnalysis
  @item-05-004
  Scenario: RDM shall ship a built-in 21 CFR Part 11 document-control checklist, and RDM's own document-control statement (git as the document control system for this repository) shall pass gap analysis against it.
