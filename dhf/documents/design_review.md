---
id: DR-001
revision: 3
title: Design Review — RDM
---

# Purpose

Records the design review of RDM's design inputs (DI-001) and the record-first
architecture.

# Design Review 1 — Design inputs and architecture

**Scope reviewed:** Design inputs (DI-001) and the system architecture
(`architecture.md`), against the record-first model in
`docs/record-first-architecture.md` and ADR 0001.

**Disposition:** Approved.

## Participants

Recorded via the version-control history: the reviewers are the approvers of the
pull request in which this review was merged. At least one approver is
independent of the authoring of the reviewed design stage (approver ≠ commit
author).

## Items reviewed

- Design inputs DI-1..DI-6 are unambiguous and individually verifiable by an
  automated test.
- Each user need (V&V plan) is addressed by at least one bounded-context design
  document via `satisfies`; no user need is duplicated across contexts.
- The record core (record, gating, verification, validation, rendering) does not
  depend on the planning extra; planning is fenced as non-record (DI-6).
- Approval is the version-control record; no duplicate sign-off is introduced.
- Verification (Allure) and validation (human summative + persona formative) are
  distinguished; persona evidence never gates release.

## Test-faithfulness review (per design input)

The detailed §820.30(e) examination of *whether each verifying test actually
verifies its design input* is recorded as machine-checkable, hash-pinned verdicts
under `dhf/faithfulness/` (`rdm story faithfulness` reconciles them). At this
revision every design input (DI-1..DI-6) carries a current `faithful` verdict;
the release gate blocks if any is missing, negative, or stale (test changed since
review). One weak test was found during this review (DI-6 originally asserted only
the wording of `PROVENANCE_NOTE`) and was strengthened to assert the actual
stamping behaviour before being marked faithful.

## Findings and actions

- No blocking findings. The release gate and traceability still read the
  user-need registry from per-context SDD frontmatter in code; migrating them to
  read the V&V-plan registry (ADR 0001 consequence) is tracked as follow-up and
  does not block this review.

# Design Review 2 — Agent enablement and document control

**Scope reviewed:** the design inputs added for agent-era contributor
enablement and document control — DI-22 (design-input scaffolding,
`rdm story new-input`), DI-23 (record-first-aware traceability audit), DI-24
(brownfield adoption, `rdm adopt`), and DI-25 (Part 11 document-control
checklist + RDM's own document-control statement, `document_control.md`) —
together with the user needs they refine (UN-010, UN-011) and the canonical
change procedure (`AGENT_WORKFLOW.md`).

**Disposition:** Approved.

## Items reviewed

- Each new design input is unambiguous, owned by exactly one context
  (scaffolding, story_audit, gap_analysis), and individually verified by an
  `@allure.story`-tagged acceptance test.
- Every user need in the V&V plan registry is addressed by at least one design
  input; the release gate enforces the full denominator.
- The document-control statement's Part 11 mapping is held to the shipped
  `part11_document_control` checklist by an executable, falsifiable check.
- Independence of the §820.30(e) faithfulness review was exercised, not just
  asserted: reviews for DI-22 and for the worked example's DI-6..8 initially
  returned `partial` with executed surviving mutations; the verifying tests
  were strengthened and re-reviewed to `faithful`. The partial verdicts and
  the strengthening commits remain in history as the audit trail of the loop
  working.
- The pre-commit design gate blocked an implementation commit staged ahead of
  its design approval during this cycle (DI-25) — the enforced sequencing
  operates as designed.

## Findings and actions

- No blocking findings. The follow-up from Review 1 (migrating the release
  gate to read the V&V-plan registry) is implemented; the release-gate
  denominator is the union of `design_inputs` reconciled against the registry.

# Design Review 3 — Soundness hardening, release artifacts, audit response

**Scope reviewed:** the design inputs added or amended after Review 2 —
DI-26 (design-gate-only hooks default), DI-27 (replayable probes, report
filters), DI-28 (per-verdict hash scope), DI-29/DI-30 (DMR index data and the
release evidence bundle, with UN-012), DI-31 (polyglot tag discovery), DI-32
(legacy-workflow deprecation), the DI-21 amendment (defense-in-depth probe
restore after the SIGTERM/stale-pyc incidents), the DI-10/DI-22 amendments
(sound gap reference matching; satisfies-list sync), and DI-33 (summative
validation records + release-gate warnings, raised by external-style audit
finding NC-1).

**Disposition:** Approved.

## Items reviewed

- Each input is owned by one context, individually verified by a tagged test,
  and carries a current independent faithfulness verdict; from DI-26 onward
  verdicts embed executed mutation probes and are replay-verified.
- The incident record (SIGTERM restore gap; same-second stale-pyc; the replay
  timeout) shows root-cause fixes flowing through this same loop, with the
  interim partial verdicts retained as evidence.
- Audit findings NC-4 (checklist completeness: §11.10(h)/(j)), NC-5 (this
  review entry), and NC-6 (retention statement) are dispositioned in this
  revision; NC-1 is dispositioned by DI-33 plus the summative reviews that
  remain a human obligation; NC-2/NC-3 (human PR review and approval; unique,
  signed identities) are open human actions tracked in the pull request.

## Findings and actions

- Open: summative validation records for UN-001..012 (human reviewers, per the
  V&V plan approach table) — the release gate now names each missing record.
- Open: merge via an independent, human-reviewed PR; individual signing
  identities (NC-2/NC-3).

# Approval

Recorded in version control (the merged, reviewed PR), per the design-input
approval model. No sign-off table is duplicated here.
