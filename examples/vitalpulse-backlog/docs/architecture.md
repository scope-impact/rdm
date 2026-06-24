---
id: SDS-SYS-001
title: VitalPulse System Architecture
context: system
# User needs (per ADR 0001): the validated, cross-cutting journeys, defined ONCE
# here. Multiple bounded-context SDDs address the same user need by referencing
# its ID in their `satisfies` list -- never by duplicating it. Validation is
# against these; verification is against the acceptance criteria they dissolve
# into (Allure tests), aggregated across every SDD that satisfies the need.
user_needs:
  - id: UN-001
    text: "Continuously inform the clinician of the patient's vital signs and promptly alert them to dangerous changes."
  - id: UN-002
    text: "Make a patient's monitored data securely available to authorized clinicians at the central station."
  - id: UN-003
    text: "Operate safely and deterministically as a life-supporting (Class C) device, tolerating single faults."
---

# System Architecture

This document describes the cross-context (system-level) design of the
VitalPulse Patient Monitoring System and declares the **user needs** (the
clinical journeys) in its frontmatter above.

Each user need is addressed across several bounded contexts. A context's SDD
(`sdd/<context>.md`) declares which user needs it contributes to via
`satisfies: [UN-…]`, and its acceptance criteria (Allure-tagged tests) verify
its part. See `docs/example-vitalpulse-decomposition.md` for the full mapping.
