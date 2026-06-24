---
id: SDS-SYS-001
title: VitalPulse System Architecture
context: system
# Product needs (Level 2 of ADR 0001): cross-context journeys, declared here in
# the system architecture document's frontmatter -- the same mechanism as the
# context needs in each per-context SDD, one altitude up. Each composes
# context-scoped user needs BY REFERENCE (never duplicated). A product need is
# verified iff every context need it composes is verified.
product_needs:
  - id: PN-001
    text: "Continuously inform the clinician of the patient's vital signs and promptly alert them to dangerous changes."
    composed_of: [ACQ-UN-001, ACQ-UN-002, ACQ-UN-003, ALRM-UN-001, ALRM-UN-002, DISP-UN-001, DISP-UN-002]
  - id: PN-002
    text: "Make a patient's monitored data securely available to authorized clinicians at the central station."
    composed_of: [CONN-UN-001, CONN-UN-002, SEC-UN-001, SEC-UN-002, SEC-UN-003, DISP-UN-002]
  - id: PN-003
    text: "Operate safely and deterministically as a life-supporting (Class C) device, tolerating single faults."
    composed_of: [PLAT-UN-001, PLAT-UN-002, ALRM-UN-003]
---

# System Architecture

This document describes the cross-context (system-level) design of the
VitalPulse Patient Monitoring System and declares the **product needs** (the
clinical journeys) in its frontmatter above.

Each product need is realized across several bounded contexts; the design of
each context is in that context's SDD (`sdd/<context>.md`), which declares its
own context-scoped `user_needs`. See `docs/example-vitalpulse-decomposition.md`
for the full decomposition.
