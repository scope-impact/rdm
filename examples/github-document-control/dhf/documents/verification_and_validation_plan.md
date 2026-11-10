---
id: VVP-001
title: "Verification and Validation Plan — git/GitHub document control"
references: [SDS-SYS-001, SOP-DC-001]
# User-need registry: the validated journeys this document-control system
# serves. Design inputs in the per-context design documents refine these.
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
| CurrentRevision..003 | quality-owner review that a walked release (draft → PR → approval → tag → release artifacts) satisfies the journey | dogfooding: this example repository operates under its own procedure |
| Part11Controls | quality/regulatory review of the Part 11 mapping in SOP-DC-001 against the regulation text | `rdm gap` over SOP-DC-001 with the Part 11 checklist (automated) |

The summative record of each user need is `dhf/validation/UN-nnn-validation.json`.
Each is **pending** until the quality owner has done the review above; the
release gate names every user need without an approved record, as a warning,
because a machine cannot supply that judgment. A record is set to `approved`,
with the reviewer and what was reviewed, only after the review took place.

This example is illustrative — it is
not legal or regulatory advice, and summative Part 11 assessment for a real
deployment remains the adopting organization's responsibility.
