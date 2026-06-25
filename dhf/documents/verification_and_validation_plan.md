---
id: VVP-001
title: Verification and Validation Plan — RDM
# User-need registry (ADR 0001): RDM's validated journeys, defined once here.
# Per-context SDDs reference these via `satisfies`. Verification = acceptance
# criteria as @allure-tagged tests in RDM's tests/, aggregated across contexts.
# Validation = human review + the usability-persona skill (formative).
user_needs:
  - id: UN-001
    text: "A regulatory author can compile a Design History File from the system of record (SDD + executed tests + git)."
  - id: UN-002
    text: "A team is prevented from transitioning into implementation until the design input and design review are approved."
  - id: UN-003
    text: "A release is blocked until every user need is verified by a passing test."
  - id: UN-004
    text: "Each user need's verification status is traceable from executed test results."
  - id: UN-005
    text: "The usability of a documented UI can be exercised formatively against a user need."
---

# Purpose

Defines how RDM is verified (does it meet its acceptance criteria?) and validated
(does it meet the user needs / intended use?).

# User needs

Declared in this document's frontmatter (`user_needs`) — the validation anchors
and the coverage denominator. Every user need must be validated and fully
verified before a release.

# Verification approach

Each user need dissolves into acceptance criteria within the bounded contexts
that `satisfy` it; each is verified by an automated test in RDM's `tests/`,
tagged `@allure.story("UN-…")`. A user need is verified when all its acceptance
criteria pass, aggregated across the contexts that satisfy it. `rdm story
release-gate` enforces this.

# Validation approach

| User need | Summative (record of truth) | Formative (supporting) |
|-----------|-----------------------------|------------------------|
| UN-001..004 | maintainer review that the compiled DHF, gates, and traceability meet the documented intent | dogfooding: RDM compiles its own DHF (this file set) |
| UN-005 | review of persona-skill output against a real UI journey | `usability-persona` skill runs (`rdm story persona`) |

Formative evidence never gates release.
