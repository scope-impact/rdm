---
id: DR-001
revision: 1
title: Design Review
---

# Purpose

Records the design review of this product's design inputs and architecture.

# Design Review 1

**Scope reviewed:** TODO — the design inputs and contexts reviewed at this
revision.

**Disposition:** TODO (Approved / Approved with actions / Not approved).

## Participants

Recorded via version control: the reviewers are the approvers of the pull
request in which this review was merged. At least one approver is independent
of the authoring of the reviewed design stage.

## Items reviewed

TODO — e.g.: each design input is unambiguous and individually verifiable by an
automated test; each user need is addressed by at least one context via
`satisfies`; verification and validation evidence are distinguished.

## Test-faithfulness review (per design input)

The detailed examination of *whether each verifying test actually verifies its
design input* is recorded as hash-pinned verdicts under `dhf/faithfulness/`
(`rdm story faithfulness` reconciles them). The release gate blocks if any is
missing, negative, or stale.

# Approval

Recorded in version control (the merged, reviewed PR) — no duplicate sign-off
table here.
