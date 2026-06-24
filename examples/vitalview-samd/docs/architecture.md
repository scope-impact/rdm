---
id: SDS-SYS-001
title: VitalView System Architecture
context: system
---

# System Architecture

VitalView is a clinician-facing web application (SaMD). This document describes
its cross-context **design**. It holds design only — the **user needs** live in
the V&V plan (`verification_and_validation_plan.md`); each context SDD declares
the user needs it contributes to via `satisfies`.

## Bounded contexts (one SDD each)

| Context | SDD | Responsibility |
|---------|-----|----------------|
| `auth` | `sdd/auth.md` | clinician authentication, authorization, PHI-access audit |
| `ingestion` | `sdd/ingestion.md` | receive, validate, and store streamed vital signs |
| `alerting` | `sdd/alerting.md` | detect dangerous changes against thresholds; notify |
| `dashboard` | `sdd/dashboard.md` | the web UI: patient list, vitals view, alert acknowledgement |

## Cross-context flow

A monitor gateway streams vitals → **ingestion** stores them → **alerting**
evaluates thresholds and raises alerts → **dashboard** presents vitals and alerts
to a clinician who has signed in through **auth**; every PHI read is audited.

A user journey (e.g. "notice and acknowledge a critical alert") crosses several
of these contexts; that is why user needs are defined once (V&V plan) and
referenced, not owned by any one SDD.
