---
id: DC-HAR-001
title: "Document control — git as the document control system for the pi harness's record"
status: proposed
references: [CSV-HAR-001]
---

# Purpose

States how this record is controlled: the pi harness's design history file,
its validation (CSV-HAR-001) and the evidence of each run. Its validation
record is required as evidence of a validated state, so it is a Part 11
electronic record ([[CSA:part11]]): this statement maps each applicable
21 CFR Part 11 control to the mechanism that meets it, and passes gap
analysis against RDM's built-in `part11_document_control` checklist.

# The system

The record is kept **in git, with GitHub as the service provider**, in the
repository that holds it (RDM's, for this example); the spec and the code it
records are the `loyly` submodule, pinned to one commit. The master copy is
the default branch; drafts are branches; review and approval happen on the
pull request; the revision history is the git history itself.

# Controls

- **Validation** [[P11:11.10a]] — the record system (git, GitHub and RDM's
  gates) is validated as CSV-HAR-001 validates the harness: on a justified,
  documented risk assessment, with assurance activities proportionate to it,
  and a record of each run. RDM's own acceptance tests verify the gates it
  runs; altered records are discernible by construction, as any change
  produces a new commit SHA.
- **Copies** [[P11:11.10b]] — the evidence bundle holds the verification
  report (PDF, human-readable) and the results, the verification data and the
  matrix (electronic); `git archive` at any commit reproduces the record.
- **Retention and retrieval** [[P11:11.10c]] — every clone is a full replica
  of the history; the evidence bundle of each validation is kept as a CI
  artifact and with its release.
- **Access** [[P11:11.10d]] — write access is limited to the repository's
  authorized members, with two-factor authentication.
- **Audit trail** [[P11:11.10e]] — the git history: SHA-chained,
  computer-generated, time-stamped and attributed; a later change never
  obscures an earlier one.
- **Sequencing** [[P11:11.10f]] — CI runs the harness's suites, then verify,
  the release gate, gap analysis, graph validation and the evidence bundle,
  in that order, and a failing step stops the rest.
- **Authority** [[P11:11.10g]] — only an authorized reviewer other than the
  author approves a pull request into the default branch.
- **Device checks** [[P11:11.10h]] — every writing client authenticates with
  a credential bound to an individual's two-factor-protected account.
- **Training** [[P11:11.10i]] — contributors, human or agent, are directed by
  the repository's contributor guides before changing the record.
- **Signature accountability** [[P11:11.10j]] — a pull-request approval is
  the approver's electronic signature, and the approver is accountable for
  what it approves.
- **Systems documentation control** [[P11:11.10k]] — this statement, the
  validation and the CI workflow are controlled in the same repository and
  change only through the path they describe.
- **Signature manifestation** [[P11:11.50]] — the review records the
  approver, the UTC time and the meaning (approved).
- **Signature–record linking** [[P11:11.70]] — a review is bound to the
  commit SHA it approves.
- **Signature uniqueness** [[P11:11.100]] — one account per individual, never
  reused or reassigned.

# Verification

`rdm gap part11_document_control dhf/documents/document_control.md` exits
zero in CI.
