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
---

# Gap analysis — Software Design

## Design Inputs

This context owns the gap-analysis requirements, all refining UN-006 (check that
documents contain the references a chosen standard requires):

- **DI-10 (gap detection)** — report the checklist references missing from a set
  of documents; exit non-zero when any are absent.
- **DI-11 (built-in composable checklists)** — ship the standard checklists and
  resolve `include` directives so e.g. `62304_2015_class_b` composes the base +
  class-A lists.
- **DI-12 (coverage report)** — report total / missing / covered / percent per
  checklist, naming the missing items in verbose mode.

## Design Outputs

`rdm gap` and `rdm/gaps.py`:

- `audit_for_gaps(checklist, sources, …)` — read a checklist (built-in name or
  path), resolve includes, scan source documents for each reference key, and
  report failures (`return 3`) or success (`return 0`).
- `coverage_report(checklists, sources, verbose)` — tabulate coverage per
  checklist prefix.
- `rdm/checklists/` — the 16 shipped checklists (62304 2006/2015 × class A/B/C +
  base, 14971 2007/2019, FDA-SW 2005/2021, FDA-CYBER 2018, FDA-HFE 2011).

Acceptance criteria are verified by `@allure.story("DI-10" / "DI-11" / "DI-12")`
tests; RDM's existing `gaps_test.py` unit tests remain as lower-level coverage.
