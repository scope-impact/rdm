---
id: VVP-001
title: Verification and Validation Plan
# User-need registry (per ADR 0001): the validated, cross-cutting clinician
# journeys, defined ONCE here. Bounded-context SDDs reference these via
# `satisfies`. Verification = acceptance criteria (Allure), aggregated across
# every SDD that satisfies the need. Validation = human summative study per need
# (with AI-persona runs as formative, supporting evidence).
user_needs:
  - id: UN-001
    text: "A clinician is promptly alerted to a patient's dangerous vital-sign changes and can acknowledge the alert."
  - id: UN-002
    text: "A clinician can securely sign in and view a patient's current and recent vital signs."
  - id: UN-003
    text: "Access to patient data is restricted to authorized clinicians and is audited."
---

# Purpose

Defines how VitalView is **verified** (does it meet its acceptance criteria?) and
**validated** (does it meet the user needs / intended use?).

# User Needs

Declared in this document's frontmatter (`user_needs`) — the validation anchors
and the coverage denominator. Every user need must be validated and fully
verified before release.

# Verification approach

Each user need is refined into **design inputs** (declared in the per-context
design documents, `kind: design`), realised by
the bounded contexts that `satisfy` the need. Verification is anchored on the
design inputs (§820.30(f): output meets input): each is verified by an
`@allure.story("DI-…")` test — the test *is* the acceptance criterion ("live
BDD"). A user need is met when it is validated and every design input that
`traces_to` it is verified, aggregated across those contexts.

# Validation approach

| User need | Summative validation (record of truth) | Formative (supporting) |
|-----------|----------------------------------------|------------------------|
| UN-001, UN-002 | clinician usability study (IEC 62366-1 summative), simulated use | AI-persona dashboard runs (`rdm story persona`) |
| UN-003 | security/access review + audit-log inspection | n/a (not a UI journey) |

AI-persona runs are **formative only** and never gate release.
