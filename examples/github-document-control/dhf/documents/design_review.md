---
id: DR-001
revision: 1
title: "Design Review — git/GitHub document control"
---

# Purpose

Records the design review of the document-control system's design inputs.

# Design Review 1 — controls and Part 11 mapping

**Scope reviewed:** design inputs DI-1..DI-5 (`design/document_control.md`)
against the user needs in the V&V plan and the applicable 21 CFR Part 11
controls.

**Disposition:** Approved.

## Participants

Recorded via version control: the reviewers are the approvers of the pull
request in which this review was merged. At least one approver is independent
of the authoring of the reviewed design stage.

## Items reviewed

- Each design input is unambiguous and individually verifiable by an automated
  test against the configuration code or the rendered document.
- Every user need (UN-001..UN-004) is refined by at least one design input.
- The Part 11 mapping in SOP-DC-001 covers each checklist item with the
  concrete git/GitHub mechanism that satisfies it, and the checklist scoping
  note states which Part 11 sections are handled outside this procedure.
- The approval-as-electronic-signature model (manifestation, linking,
  uniqueness) is stated in the SOP and enforced by the ruleset.

## Test-faithfulness review (per design input)

Recorded as hash-pinned verdicts under `dhf/faithfulness/`
(`rdm story faithfulness` reconciles them); the release gate blocks on any
missing, negative, or stale verdict.

# Approval

Recorded in version control (the merged, reviewed PR) — no duplicate sign-off
table here.
