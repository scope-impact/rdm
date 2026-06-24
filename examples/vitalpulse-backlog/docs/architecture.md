---
id: SDS-SYS-001
title: VitalPulse System Architecture
context: system
---

# System Architecture

This document describes the cross-context (system-level) **design** of the
VitalPulse Patient Monitoring System.

It holds design only. The **user needs** (the validated clinical journeys) are
defined in the validation & verification plan
(`verification_and_validation_plan.md`); each bounded-context SDD
(`sdd/<context>.md`) declares which user needs it contributes to via
`satisfies: [UN-…]`. See `docs/example-vitalpulse-decomposition.md` for the
mapping.
