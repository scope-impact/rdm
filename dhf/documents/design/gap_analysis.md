---
id: SDS-GAP-001
kind: design
context: gap_analysis
satisfies: [UN-006]
design_inputs:
  - id: DI-10
    text: "RDM shall report the checklist references (keys) absent from a set of documents, exiting non-zero when any required reference is missing."
    traces_to: [UN-006]
  - id: DI-11
    text: "RDM shall ship composable built-in checklists for the applicable standards (IEC 62304, ISO 14971, FDA-SW/CYBER/HFE), resolving `include` directives between them."
    traces_to: [UN-006]
  - id: DI-12
    text: "RDM shall report coverage of documents against a checklist (total / missing / covered / percent), listing the missing items in verbose mode."
    traces_to: [UN-006]
  - id: DI-25
    text: "RDM shall ship a built-in 21 CFR Part 11 document-control checklist, and RDM's own document-control statement (git as the document control system for this repository) shall pass gap analysis against it."
    traces_to: [UN-006]
---

# Gap analysis — Software Design

## Design Inputs

This context owns the gap-analysis requirements, all refining UN-006 (check that
documents contain the references a chosen standard requires):

- **DI-10 (gap detection)** — report the checklist references missing from a set
  of documents; exit non-zero when any are absent. A reference is a key inside
  a `[[ … ]]` block, matched exactly (a bare prose mention or a longer sibling
  key never counts), with two deliberate allowances: a dotted *descendant*
  covers its parent (`[[62304:5.6.2.a]]` addresses `62304:5.6.2`), and a key
  may be followed by a `: annotation` tail (`[[FDA-SW:sdmp: pointer note]]` —
  the idiom the shipped `rdm init` templates use) without breaking the match,
  while a longer colon-qualified key still never satisfies its prefix.
- **DI-11 (built-in composable checklists)** — ship the standard checklists and
  resolve `include` directives so e.g. `62304_2015_class_b` composes the base +
  class-A lists.
- **DI-12 (coverage report)** — report total / missing / covered / percent per
  checklist, naming the missing items in verbose mode.
- **DI-25 (Part 11 checklist + RDM's own document-control claim)** — ship the
  21 CFR Part 11 document-control checklist (the electronic-records /
  electronic-signatures controls applicable to a git-based document control
  system) as a built-in, and make RDM's own claim executable: RDM keeps its
  entire record — this DHF included — in git with GitHub as the service
  provider, and its document-control statement
  (`dhf/documents/document_control.md`) must pass gap analysis against the
  shipped checklist. The dogfood direction matters: the same checklist any
  downstream project can audit with is the one RDM's own record is held to.

## Design Outputs

`rdm gap` and `rdm/gaps.py`:

- `audit_for_gaps(checklist, sources, …)` — read a checklist (built-in name or
  path), resolve includes, scan source documents for each reference key, and
  report failures (`return 3`) or success (`return 0`).
- `coverage_report(checklists, sources, verbose)` — tabulate coverage per
  checklist prefix.
- `rdm/checklists/` — the 16 shipped checklists (62304 2006/2015 × class A/B/C +
  base, 14971 2007/2019, FDA-SW 2005/2021, FDA-CYBER 2018, FDA-HFE 2011), plus
  `part11_document_control.txt` (DI-25).
- `dhf/documents/document_control.md` — RDM's own document-control statement:
  git/GitHub as this repository's document control system, each Part 11
  checklist item cited inline (DI-25).

Acceptance criteria are verified by `@allure.story("DI-10" / "DI-11" / "DI-12"
/ "DI-25")` tests; RDM's existing `gaps_test.py` unit tests remain as
lower-level coverage.
