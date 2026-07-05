---
id: DC-001
revision: 1
title: "Document Control — git as the document control system for RDM's record"
---

# Purpose

States how RDM's own controlled records — this Design History File, the design
documents, the faithfulness verdicts, and the source that realises them — are
controlled. RDM keeps its record **in git, with GitHub as the service
provider**; this statement maps each applicable 21 CFR Part 11 control to the
mechanism that meets it, and it must pass gap analysis against RDM's own
shipped `part11_document_control` checklist (DI-25 — the same checklist any
downstream project audits with is the one this record is held to).

# The system

The master copy of every controlled record is the repository's default branch.
Drafts are branches; review and approval happen on the pull request; releases
are tags; the revision history is the git history itself. Planning artifacts
(Backlog.md tasks, issues) are coordination, never part of the record
(`docs/plan-vs-record.md`).

# Controls

- **Validation** [[P11:11.10a]] — the controls are enforced configuration and
  code, exercised on every change: the design gate, the pre-commit hook
  (`.githooks/`), and the CI pipeline (`design-controls.yml`) are RDM's own
  features, verified by RDM's own tagged acceptance tests and independent
  faithfulness verdicts. Altered records are discernible by construction —
  any change produces a new SHA, and hash-pinned verdicts go stale on any edit
  to what they reviewed.
- **Copies** [[P11:11.10b]] — accurate and complete copies in human-readable
  and electronic form: the rendered documentation site and the rendered
  traceability matrix are generated from the record by `rdm render`; a
  `git archive` at any tag reproduces the complete electronic record set.
- **Retention and retrieval** [[P11:11.10c]] — every clone is a full replica
  of the history; release tags identify record baselines; the GitHub-hosted
  repository is the retained store. Retention period: the full history is
  retained for as long as RDM is maintained, and release baselines (tags and
  their evidence bundles) for no less than five years after the release they
  record. An organization-controlled mirror is an open action (audit NC-6).
- **Access** [[P11:11.10d]] — write access is limited to authorized
  individuals through GitHub organization membership with two-factor
  authentication; the audit log records permission changes.
- **Device checks** [[P11:11.10h]] — every writing client is authenticated to
  an account-bound credential (SSH key or token issued to the individual's
  2FA-protected account); anonymous or unauthenticated input cannot reach the
  record.
- **Signature accountability** [[P11:11.10j]] — the contributor policy
  (`dhf/AGENT_WORKFLOW.md` and this statement, both controlled documents)
  states that a pull-request approval is the approver's electronic signature
  and that the approver is accountable for what it approves; verdict reviewers
  are named in each hash-pinned verdict.
- **Audit trail** [[P11:11.10e]] — the git history: secure (SHA-chained),
  computer-generated, time-stamped, author-attributed on every action; a later
  change never obscures an earlier entry, and the trail lives exactly as long
  as the record it belongs to.
- **Sequencing** [[P11:11.10f]] — the enforced order of steps is RDM's own
  design-controls loop: the pre-commit hook blocks implementation before
  design approval, and CI blocks merge until design-gate, acceptance tests,
  verification, faithfulness, and release-gate pass in order.
- **Authority** [[P11:11.10g]] — only authorized reviewers can approve a pull
  request into the default branch; the reviewer of a faithfulness verdict must
  be independent of the test's author (enforced procedure, recorded in each
  verdict). The branch controls are configuration code in this repository —
  `.github/rulesets/controlled-record.json` (pull-request review, verified
  commit signatures, required checks, no history rewriting) and
  `repo-settings.json` — applied and drift-checked with
  `scripts/apply-repo-controls.sh [--check]`.
- **Training** [[P11:11.10i]] — contributors (human or agent) are directed
  through `dhf/AGENT_WORKFLOW.md` before changing the record; agent sessions
  load it automatically at session start.
- **Systems documentation control** [[P11:11.10k]] — this statement, the
  runbook, the hook, and the CI workflow are themselves version-controlled in
  this repository and change only through the same approved path they
  describe.
- **Signature manifestation** [[P11:11.50]] — the pull-request review records
  the approver, the UTC timestamp, and the meaning of the signing (APPROVED —
  reviewed and approved for the record); the merged, reviewed PR is RDM's
  approval record by design (no duplicate sign-off blocks exist in the DHF).
- **Signature–record linking** [[P11:11.70]] — a review is bound to the exact
  commit SHA it approves; content-addressing makes a signature inseparable
  from what was signed.
- **Signature uniqueness** [[P11:11.100]] — one GitHub account per individual,
  two-factor authentication, accounts never reused or reassigned.

# Verification

`rdm gap part11_document_control dhf/documents/document_control.md` must exit
zero; the DI-25 acceptance test executes exactly that, so this statement can
never silently drop a control.
