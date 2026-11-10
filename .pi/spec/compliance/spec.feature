Feature: Compliance
  The accepted scenarios of this context: gap analysis of documents against
  the checklists of the applicable standards, and the checklists RDM ships.
  Each is stamped with an item id that is never renumbered or reused, and
  verified by the acceptance tests whose Allure results carry that id as a tag
  label.

  Rule: A document set has a gap for every checklist key no document references

    # compliance.allium, rule Gap (requires 1 to 3)
    @allium-rule-success.Gap @allium-rule-failure.Gap.1 @allium-rule-failure.Gap.2 @allium-rule-failure.Gap.3
    @item-05-001
    Scenario: Gap analysis names each missing reference and exits non-zero when one is missing or nothing was checked
      Given a checklist "cl.txt" with the clauses X-1 and X-2
      When the regulatory author runs rdm gap cl.txt over "partial.md", which references [[X-1]] only
      Then it exits 3, listing X-2 and not X-1, under the heading "# 1 gap:"
      And over "full.md", which references [[X-1]] and [[X-2]], it exits 0
      And a stray "[[" does not turn the next paragraphs' mentions into references: X-2 is still missing
      And a bare mention "We do not address X-2" is no reference to X-2: it exits 3
      And [[X-12]] does not reference the key X-1: it exits 3
      And [[FDA-SW:sdmp: This document is a pointer document.]] references the key FDA-SW:sdmp: it exits 0
      And [[FDA-SW:sdmp]] does not reference the key FDA-SW: it exits 3
      And a byte-order mark is not part of the first key: a checklist beginning with a byte-order mark before "X-1 first" is covered by full.md
      And "include   cl.txt  " with spaces around the name is resolved
      When nothing could be checked: a checklist with no clauses, a checklist that does not exist, a bare "include", an include of a file that does not exist, a document that does not exist, or a directory given as a document
      Then it exits 2, never 0
      When a document covering every clause still holds a "TODO: write this section" placeholder
      Then it exits 0 and warns, naming the document and its placeholder
      When its report of 20000 gaps is piped into a reader that stops after one line
      Then it ends quietly, with no traceback and no BrokenPipe error

  Rule: RDM ships composable checklists for the applicable standards

    # compliance.allium, surface GapAnalysis, guarantee BuiltInChecklistsCompose
    @allium-guarantee.BuiltInChecklistsCompose
    @item-05-002
    Scenario: The built-in checklists are listed by name, hold unique keys and resolve their includes
      When the regulatory author lists the built-in checklists
      Then 62304_2015_class_b, 14971_2019, FDA-SW_2021_enhanced and csv_tool_validation (computer software validation of a tool) are listed by name
      And IEC 62304, ISO 14971, FDA-SW, FDA-CYBER and FDA-HFE each have a built-in checklist with clauses
      And no built-in checklist holds a key twice
      And 62304_2015_class_b, which includes 62304_2015_class_a by name, requires every clause of class A and more
      Given a checklist "main.txt" with "include base.txt" and the clause M-1, and "base.txt" with the clause B-1
      When a document referencing only [[M-1]] is checked against main.txt
      Then it exits 3: B-1, defined only in the included file, is still required
      And a document referencing [[M-1]] and [[B-1]] exits 0
      Given "a.txt" includes "sub/b.txt", which includes "../a.txt" and "./../a.txt"
      When the coverage of a document referencing [[A-1]] is reported against a.txt
      Then a.txt is read once: its row is "| A.TXT | 2 | 1 | 1 | 50% |" and it exits 3

  Rule: Coverage of a document set is tabulated per checklist

    # compliance.allium, rule Coverage (requires 1)
    @allium-rule-success.Coverage @allium-rule-failure.Coverage.1
    @item-05-003
    Scenario: The coverage report gives each checklist's total, missing, covered and percent
      Given a checklist "iso_checklist.txt" with the clauses ISO-1, ISO-2 and ISO-3
      When the regulatory author reports coverage of "process.md", which references [[ISO-1]] and [[ISO-3]]
      Then the row "| ISO | 3 | 1 | 2 | 66% |" is printed and it exits 3
      And in verbose mode the missing ISO-2 is named
      When the coverage of a document referencing all three clauses is reported
      Then it exits 0, and in verbose mode prints no empty "Missing Items" heading
      When the checklist has no clauses or does not exist
      Then it exits 2: nothing could be checked

  Rule: RDM keeps its own record under the 21 CFR Part 11 checklist it ships

    # compliance.allium, surface GapAnalysis, guarantee Part11ChecklistShipped
    @allium-guarantee.Part11ChecklistShipped
    @item-05-004
    Scenario: RDM's own document-control statement passes the built-in Part 11 checklist, and fails without a control
      When the regulatory author lists the built-in checklists
      Then part11_document_control is listed by name
      When dhf/documents/document_control.md is checked against part11_document_control
      Then it exits 0: the statement references every clause
      When a copy of the statement without its reference [[P11:11.10e]] is checked
      Then it exits 3: dropping one control fails the audit
