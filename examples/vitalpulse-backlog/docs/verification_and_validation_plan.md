---
id: VVP-001
title: Verification and Validation Plan
# User needs (per ADR 0001): the validated, cross-cutting clinical journeys,
# defined ONCE here -- this is the validation plan, and validation is defined
# against user needs. Multiple bounded-context SDDs address the same user need
# by referencing its ID in their `satisfies` list. Verification (acceptance
# criteria, Allure) is aggregated across every SDD that satisfies the need;
# validation evidence (usability/clinical/simulated-use) attaches per need.
user_needs:
  - id: UN-001
    text: "Continuously inform the clinician of the patient's vital signs and promptly alert them to dangerous changes."
  - id: UN-002
    text: "Make a patient's monitored data securely available to authorized clinicians at the central station."
  - id: UN-003
    text: "Operate safely and deterministically as a life-supporting (Class C) device, tolerating single faults."
---

# Purpose

This plan defines how the VitalPulse Patient Monitoring System is **verified**
(does it meet its acceptance criteria?) and **validated** (does it meet the user
needs / intended use?).

# User Needs

The user needs are declared in this document's frontmatter (`user_needs`). They
are the **validation** anchors and the denominator for coverage: every user need
must be both validated and fully verified before release.

# Verification approach

Each user need dissolves into acceptance criteria within the bounded contexts
that `satisfy` it; each acceptance criterion is verified by an `@allure`-tagged
test (`@allure.story("UN-…")`). A user need is verified when all of its
acceptance criteria pass, aggregated across every context that satisfies it.

# Validation approach

Each user need (the clinical journey) is validated by human evidence — usability
evaluation (IEC 62366-1 summative), clinical evaluation, and simulated-use
testing — recorded as controlled documents and approved in version control.
