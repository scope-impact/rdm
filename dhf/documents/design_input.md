---
id: DI-001
revision: 1
title: Design Input — RDM
# Design-input registry: the verification anchor. Each is verified by a test
# tagged @allure.story("DI-n") and traces up to the user need(s) it refines.
design_inputs:
  - id: DI-1
    text: "RDM shall read the registry + satisfies refs from frontmatter and ingest Allure results, with no project-management dependency."
    traces_to: [UN-001, UN-004]
  - id: DI-2
    text: "RDM shall block the transition into implementation until design input and review are present, complete, and approved (committed) in git; a later edit re-opens the gate."
    traces_to: [UN-002]
  - id: DI-3
    text: "RDM shall block release unless every declared design input is verified by a passing test."
    traces_to: [UN-003]
  - id: DI-4
    text: "RDM shall reconcile against Allure tags and render a traceability matrix from executed results."
    traces_to: [UN-004]
  - id: DI-5
    text: "RDM shall support exercising UI usability formatively against a user need, recorded as evidence that never gates release."
    traces_to: [UN-005]
  - id: DI-6
    text: "RDM shall keep planning artifacts out of the record; planning tooling shall be optional and its outputs marked non-record."
    traces_to: []
---

# Purpose

This document captures the design inputs for RDM (Regulatory Documentation
Manager) — the requirements and constraints the tool must satisfy. RDM is
treated here as the product under design control; this DHF dogfoods RDM's own
record-first model.

# Scope

Applies to RDM's record-first capability: compiling a Design History File from
the system of record (SDD + executed test results + git), and gating design
controls and verification.

# Design inputs

The user needs (the *what*, validated) are enumerated in the V&V plan
(`verification_and_validation_plan.md`). The design inputs below are the
requirements the design must meet; each is verified by automated tests.

- **DI-1 (record ingest)** — RDM shall read the user-need registry and
  per-context `satisfies` references from document frontmatter, and ingest
  executed Allure results, without depending on any project-management tool.
- **DI-2 (design gate)** — RDM shall block the transition into implementation
  unless the design input and design review are present, free of scaffold
  placeholders, and approved (committed clean) in version control; a later edit
  to an approved document shall re-open the gate.
- **DI-3 (release gate)** — RDM shall block release unless every declared user
  need is verified by a passing test, aggregated across the contexts that
  satisfy it.
- **DI-4 (traceability)** — RDM shall reconcile user needs against Allure tags
  and render a traceability matrix from executed results, not hand-maintained
  tables.
- **DI-5 (formative validation)** — RDM shall support exercising UI usability
  formatively against a user need (the `usability-persona` skill), recorded as
  evidence that never gates release.
- **DI-6 (plan/record separation)** — RDM shall keep planning artifacts out of
  the record; planning tooling shall be optional and its outputs marked as
  non-record.

# Constraints

- The record core shall not require the project-management extra (`rdm[plan]`).
- Approval shall be the version-control record (reviewed/merged PR), not a
  duplicate sign-off captured in documents.

# Acceptance criteria for these inputs

- Each design input is verifiable by an automated test in RDM's `tests/`.
- The inputs do not conflict; planning concerns are explicitly out of the record
  core (DI-6).

# Approval

Approval of these design inputs is recorded in version control: the pull request
in which this revision was reviewed and merged is the approval record. Reviewer
independence is evidenced by the PR being approved by someone other than the
commit author. No sign-off is duplicated here.
