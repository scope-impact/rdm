---
id: VVP-001
title: "Verification and Validation Plan — git/GitHub document control"
# User-need registry: the validated journeys this document-control system
# serves. Design inputs in the per-context design documents refine these.
user_needs:
  - id: UN-001
    text: "An auditor can identify the current approved revision of any controlled document, who approved it, and its complete change history."
  - id: UN-002
    text: "No change reaches the controlled document set without review and approval by an independent, authorized signer."
  - id: UN-003
    text: "A released document set is retrievable, complete, and human-readable throughout the retention period."
  - id: UN-004
    text: "The document control system satisfies the applicable 21 CFR Part 11 controls for electronic records and electronic signatures."
  - id: UN-005
    text: "The current approved specification set (device master record) and the record of each release (device history record) are identifiable and retrievable from the system."
---

# Purpose

Defines how the git/GitHub document-control system is verified (do the
configured controls meet their design inputs?) and validated (does the system
meet the user needs above?).

# Verification approach

The controls are **configuration as code** (`github/`: ruleset, CODEOWNERS,
release workflow) plus the controlled SOP itself. Each design input is
verified by a tagged acceptance test in `tests/acceptance/` that inspects the
real configuration or renders the real document — including a gap analysis
proving the SOP addresses every item of the Part 11 checklist.

# Validation approach

| User need | Summative (record of truth) | Formative (supporting) |
|-----------|-----------------------------|------------------------|
| UN-001..003 | quality-owner review that a walked release (draft → PR → approval → tag → release artifacts) satisfies the journey | dogfooding: this example repository operates under its own procedure |
| UN-004 | quality/regulatory review of the Part 11 mapping in SOP-DC-001 against the regulation text | `rdm gap` over SOP-DC-001 with the Part 11 checklist (automated) |

Formative evidence never gates release. This example is illustrative — it is
not legal or regulatory advice, and summative Part 11 assessment for a real
deployment remains the adopting organization's responsibility.
