---
id: SDS-SYS-001
title: "System architecture — git/GitHub document control"
context: system
# The bounded contexts, each with the layer of the system it belongs to. Each
# has one design document, design/<context>.md.
contexts:
  - {id: approval, part: Configuration}
  - {id: records, part: Documents}
  - {id: release, part: Release}
references: [SOP-DC-001]
---

# System architecture

The product is a document control system: **git** keeps the record (identity,
history, immutability, signatures) and **GitHub** provides the service
(reviews, rulesets, releases, access control). The procedure that governs it
is SOP-DC-001. This document holds design only: the user needs live in the V&V
plan, and a context serves the needs its design inputs trace to.

| Context | Part | Owns | Design document |
| --- | --- | --- | --- |
| `approval` | Configuration | what a change must pass to reach the default branch: the ruleset, CODEOWNERS, merge settings, the required checks, the drift audit | `design/approval.md` |
| `records` | Documents | the controlled documents: identity, generated history, Part 11 coverage, the device master record | `design/records.md` |
| `release` | Release | the copies, the device history record and the verification evidence of each release | `design/release.md` |

A design input is owned by one context. Where another context implements part
of it, that context declares it under `realises`: `approval` runs the Part 11
gap analysis `records` owns (DI-5), and `records` renders the copies `release`
publishes (DI-3).
