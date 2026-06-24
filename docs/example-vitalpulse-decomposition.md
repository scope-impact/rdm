# Worked decomposition — VitalPulse product needs → bounded-context user needs

Applies ADR 0001 to the VitalPulse Patient Monitoring System (IEC 62304 Class C;
SpO2/ECG/NIBP/Temperature; ICU). It shows product-level journeys decomposed into
context-scoped user needs, with cross-context journeys held together by
reference (`composed_of`) — never duplication.

## Bounded contexts (one SDD each)

| Context | SDD | Scope |
|---------|-----|-------|
| `platform` | `sdd/platform.md` | RTOS, real-time scheduling, watchdog, fault handling |
| `acquisition` | `sdd/acquisition.md` | AFE drivers and signal acquisition (SpO2, ECG, NIBP, Temp) |
| `alarms` | `sdd/alarms.md` | limit detection, prioritisation/annunciation (IEC 60601-1-8) |
| `display` | `sdd/display.md` | waveforms, numerics, alarm presentation |
| `connectivity` | `sdd/connectivity.md` | central-station streaming, store-and-forward |
| `security` | `sdd/security.md` | clinician auth, PHI encryption, access audit (HIPAA) |

## Level 1 — context user needs (capabilities)

These live in each context's SDD frontmatter (`user_needs`). IDs are
context-scoped; Allure tests tag the context need they verify.

| ID | User need |
|----|-----------|
| `PLAT-UN-001` | Schedule safety-critical tasks deterministically within their deadlines |
| `PLAT-UN-002` | Detect and recover from a single fault (watchdog) into a safe state |
| `ACQ-UN-001` | Measure SpO2 within the stated accuracy across the specified range |
| `ACQ-UN-002` | Acquire the ECG waveform at the specified sample rate/bandwidth |
| `ACQ-UN-003` | Measure NIBP and temperature within stated accuracy |
| `ALRM-UN-001` | Detect a vital crossing a clinician-set limit within ≤ the specified delay |
| `ALRM-UN-002` | Annunciate alarms by priority per IEC 60601-1-8 |
| `ALRM-UN-003` | Preserve the alarm condition through a single fault (fail-safe/latched) |
| `DISP-UN-001` | Display real-time waveforms for the active parameters |
| `DISP-UN-002` | Display numeric vitals and current alarm state |
| `CONN-UN-001` | Stream vitals to the central station in near-real-time |
| `CONN-UN-002` | Buffer and forward on network loss with no data loss |
| `SEC-UN-001` | Authenticate clinicians before granting access |
| `SEC-UN-002` | Protect PHI in transit and at rest (encryption) |
| `SEC-UN-003` | Record an audit trail of PHI access |

Example SDD frontmatter (one context):

```yaml
# sdd/alarms.md
---
id: SDS-ALRM-001
context: alarms
user_needs:
  - {id: ALRM-UN-001, text: "Detect a vital crossing a clinician-set limit within ≤ the specified delay"}
  - {id: ALRM-UN-002, text: "Annunciate alarms by priority per IEC 60601-1-8"}
  - {id: ALRM-UN-003, text: "Preserve the alarm condition through a single fault"}
---
```

## Level 2 — product needs (journeys) decomposed

Each product need composes context needs **by reference**. See
`product_needs.yml` for the machine-readable registry.

### PN-001 — "Continuously inform the clinician of the patient's vital signs and promptly alert them to dangerous changes."
Spans **acquisition + alarms + display**:
`ACQ-UN-001, ACQ-UN-002, ACQ-UN-003, ALRM-UN-001, ALRM-UN-002, DISP-UN-001, DISP-UN-002`

### PN-002 — "Make a patient's monitored data securely available to authorized clinicians at the central station."
Spans **connectivity + security + display**:
`CONN-UN-001, CONN-UN-002, SEC-UN-001, SEC-UN-002, SEC-UN-003, DISP-UN-002`

### PN-003 — "Operate safely and deterministically as a life-supporting (Class C) device, tolerating single faults."
Spans **platform + alarms**:
`PLAT-UN-001, PLAT-UN-002, ALRM-UN-003`

## Decomposition matrix (product need × context)

| Product need | platform | acquisition | alarms | display | connectivity | security |
|---|---|---|---|---|---|---|
| PN-001 | | ACQ-UN-001/002/003 | ALRM-UN-001/002 | DISP-UN-001/002 | | |
| PN-002 | | | | DISP-UN-002 | CONN-UN-001/002 | SEC-UN-001/002/003 |
| PN-003 | PLAT-UN-001/002 | | ALRM-UN-003 | | | |

Two things this makes visible — exactly the cases that break "needs in one SDD":

- **A context need serves multiple journeys.** `DISP-UN-002` is part of PN-001
  *and* PN-002; `alarms` contributes to PN-001 *and* PN-003. It is declared
  **once** in its context and referenced by both product needs.
- **Every product need spans multiple contexts.** None is owned by a single
  SDD; the journey lives in the `composed_of` mapping, the design lives in each
  context's SDD.

## Verification roll-up

- A **context need** is verified when its Allure-tagged tests pass
  (e.g. `@allure.story("ALRM-UN-001")`), reconciled per the existing model.
- A **product need** is verified **iff every context need it composes is
  verified**. PN-001 is not "verified" until all seven of its context needs are.

This is the release-gate condition lifted to the product level: ship only when
every product journey is fully verified across its contexts.
