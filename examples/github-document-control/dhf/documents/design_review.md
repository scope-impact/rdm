---
id: DR-001
revision: 1
title: "Design Review — git/GitHub document control"
---

# Purpose

Records the design review of the document-control system's design inputs.

# Design Review 1 — controls and Part 11 mapping

**Scope reviewed:** design inputs item-01-001..item-02-003 (`design/document_control.md`)
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
- Every user need (CurrentRevision..Part11Controls) is refined by at least one design input.
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

**Scope reviewed:** design inputs item-01-002..item-03-002, added after Design Review 1
without a review of their own, and the new item-01-003 and item-03-003
(`design/document_control.md`).

**Disposition:** Approved.

## Items reviewed

- item-01-002..item-03-002 are each one acceptance criterion, individually verifiable from
  the configuration code or a rendered document, and refine CurrentRevision, Part11Controls and
  DeviceRecords.
- item-01-003: the ruleset (item-01-001) required two status checks, `render-documents` and
  `part11-gap-analysis`, that no workflow in this system reported, so every
  merge would have been blocked, and the acceptance tests the SOP says run "on
  every change" ran nowhere. The design-controls workflow now reports the
  checks, and the ruleset requires exactly those. RDM is pinned to a released
  tag, so the tool that verifies a change is itself a controlled version.
- item-03-003: a release carried copies and a manifest but no evidence that the
  released commit was verified. The release is now refused unless the release
  gate passes at the tag, and the verification report is attached.
- The SOP changes with them (revision 2): permitted sequencing names the
  required checks, system validation names the workflow, and the DHR row
  includes the verification report.
- Vocabulary follows RDM's glossary: a design input is the acceptance
  criterion; its test's verification steps are the test's own checks.

# Design Review 3 — three bounded contexts, the risk register, the drift audit

**Scope reviewed:** the architecture (`architecture.md`); the design split into
`approval`, `records` and `release`; item-02-004 and item-03-003 amended; the new item-01-004; the
risk policy and register (`risk/`); the validation records (`validation/`).

**Disposition:** Approved, with the risk policy and register as proposals.

## Items reviewed

- One design document described the whole system. The design inputs now sit
  with the context that owns them: `approval` (item-01-001, item-01-002, item-01-003, item-01-004),
  `records` (item-02-001, item-02-002, item-02-003, item-02-004) and `release` (item-03-001, item-03-002, item-03-003). No
  design input changed id, and only item-02-004 and item-03-003 changed text. Where a
  context implements part of an input another owns, it says so under
  `realises`.
- item-02-004: the DMR index data is generated from the procedures' frontmatter
  (`rdm story dmr`) instead of kept by hand, so the index cannot disagree with
  what it lists.
- item-03-003: a release attaches the whole evidence bundle (results, verification
  data, matrix, manifest), with the verification report in it.
- item-01-004: the configuration item-01-001 and item-01-002 declare is enforced by GitHub, where
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

# Design Review 4 — a design input is stated once

**Scope reviewed:** the three design documents' bodies. No design input
changed.

**Disposition:** Approved.

## Items reviewed

- Each design document restated its design inputs in a `## Design Inputs`
  section beside the frontmatter that declares them. The frontmatter is now
  the only statement; the section is removed, and each document's design
  outputs still name every input it owns, by id.

## Findings and actions

- None open.

# Design Review 5 — the design outputs are the architecture

**Scope reviewed:** the system architecture; the three design documents; the
new architecture workspace (`dhf/c4/`). No design input changed.

**Disposition:** Approved.

## Items reviewed

- The design outputs were lists of files. They are now the architecture, in
  the C4 model: one workspace (`dhf/c4/workspace.dsl`) with a component for
  each control, document and workflow, grouped by context, each naming what
  implements it; each design document shows its context's component view
  and a table of what each component is responsible for and which design
  inputs it meets. The documents name components, not files.
- The workspace's views are drawn and stamped by `rdm c4 draw`; the design
  gate fails when one is stale.

## Findings and actions

- None open.

# Approval

Recorded in version control (the merged, reviewed PR) — no duplicate sign-off
table here.
