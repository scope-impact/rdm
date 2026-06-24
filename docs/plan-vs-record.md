# Plan vs. record: the boundary

RDM separates two kinds of data. Confusing them is the main way this system can
fail an audit, so the boundary is stated explicitly here.

## System of record (controlled)

These are the controlled inputs RDM compiles into the DHF. They carry the full
weight of design controls (review, approval, retention, validation):

| Store | What it is | Source of truth for |
|-------|------------|---------------------|
| **SDD** (`dhf/documents/…`, `user_needs`) | the design and the user needs | requirements / design |
| **Allure results** | executed verification evidence | acceptance-criteria status (pass/fail) |
| **git history** | reviewed/merged PRs (`reviews_required`) | approval, change history, baselines |

The DHF (rendered Markdown → PDF/DOCX), including the generated traceability
matrix, is derived **only** from these.

## Planning data (NOT a record)

These exist for coordination and visibility. They are **derived planning data,
not controlled records**, and must never be cited as evidence:

| Store | What it is | NOT |
|-------|------------|-----|
| **Backlog.md tasks** | work breakdown / status | not the requirement or AC of record |
| **GitHub Issues / Projects** | team-visibility mirror of the plan | not the DHF, not approval |
| **DuckDB (`story sync` / `pm sync`)** | derived analytics / change-history cache | not a controlled record |

Rules:

- The **only** path from planning to the record is via **git** (commits/PRs).
  RDM never ingests a PM tool directly.
- Synced outputs are stamped *"derived planning data — not a controlled
  record"* (GitHub issue bodies; sync command banners).
- A planning artifact is **never** screenshotted, quoted, or linked as
  verification/approval evidence. If you need evidence, it comes from the SDD,
  the Allure results, or git.

## Why this matters

- **Validation scope.** Software used in the quality system must be validated
  for intended use (ISO 13485 §4.1.6 / 21 CFR 820.70(i)). Because the planning
  pipeline produces no relied-upon record, it falls **outside** that scope. The
  moment a planning output is treated as evidence, it crosses the line and
  inherits record obligations it was not built for.
- **Detachability.** The planning pipeline ships as the optional `rdm[plan]`
  extra. Delete it and the DHF, traceability, approval, and audit posture are
  unchanged — that is the test that proves it is not a record.

## Litmus test

> If you deleted every planning artifact, would the DHF or the audit posture
> change? If no, it is plan. If yes, it is record and belongs above.
