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

## Verifying-test review (per design input)

Whether each design input's tagged test actually verifies it is judged by the
independent reviewer of the pull request; the release gate blocks unless
every design input is verified by a passing tagged test.

# Design Review 2 — merge behavior, DMR, DHR, the required checks, verified releases

**Scope reviewed:** design inputs DI-6..DI-8, added after Design Review 1
without a review of their own, and the new DI-9 and DI-10
(`design/document_control.md`).

**Disposition:** Approved.

## Items reviewed

- DI-6..DI-8 are each one acceptance criterion, individually verifiable from
  the configuration code or a rendered document, and refine UN-001, UN-004 and
  UN-005.
- DI-9: the ruleset (DI-1) required two status checks, `render-documents` and
  `part11-gap-analysis`, that no workflow in this system reported, so every
  merge would have been blocked, and the acceptance tests the SOP says run "on
  every change" ran nowhere. The design-controls workflow now reports the
  checks, and the ruleset requires exactly those. RDM is pinned to a released
  tag, so the tool that verifies a change is itself a controlled version.
- DI-10: a release carried copies and a manifest but no evidence that the
  released commit was verified. The release is now refused unless the release
  gate passes at the tag, and the verification report is attached.
- The SOP changes with them (revision 2): permitted sequencing names the
  required checks, system validation names the workflow, and the DHR row
  includes the verification report.
- Vocabulary follows RDM's glossary: a design input is the acceptance
  criterion; its test's verification steps are the test's own checks.

# Design Review 3 — three bounded contexts, the risk register, the drift audit

**Scope reviewed:** the architecture (`architecture.md`); the design split into
`approval`, `records` and `release`; DI-7 and DI-10 amended; the new DI-11; the
risk policy and register (`risk/`); the validation records (`validation/`).

**Disposition:** Approved, with the risk policy and register as proposals.

## Items reviewed

- One design document described the whole system. The design inputs now sit
  with the context that owns them: `approval` (DI-1, DI-6, DI-9, DI-11),
  `records` (DI-2, DI-4, DI-5, DI-7) and `release` (DI-3, DI-8, DI-10). No
  design input changed id, and only DI-7 and DI-10 changed text. Where a
  context implements part of an input another owns, it says so under
  `realises`.
- DI-7: the DMR index data is generated from the procedures' frontmatter
  (`rdm story dmr`) instead of kept by hand, so the index cannot disagree with
  what it lists.
- DI-10: a release attaches the whole evidence bundle (results, verification
  data, matrix, manifest), with the verification report in it.
- DI-11: the configuration DI-1 and DI-6 declare is enforced by GitHub, where
  an administrator can change it without review; a daily drift audit finds
  that. It is the risk control for RISK-DC-002.
- The risk register (RMF-001) analyses six risks, three of them STRIDE threats
  linked to the safety risk they lead to. Each risk control is a design input,
  so each risk-based acceptance criterion is verified by its test. RISK-DC-006
  stays at Medium after its control and is accepted with a reason. The policy
  and the register are **proposed**: a proposal written for this example,
  shown as such until the quality owner approves it.
- The validation records are pending. None is approved, because no
  summative review has taken place; the release gate warns for each.

# Approval

Recorded in version control (the merged, reviewed PR) — no duplicate sign-off
table here.
