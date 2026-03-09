---
id: vp-002
title: "SpO2 Pulse Oximetry Module"
status: To Do
created_date: '2026-01-18'
labels: [vp-002, EP-001, spo2, sensors, patient-safety]
milestone: m-1
priority: critical
---

## Description

Pulse oximetry measurement using red/infrared LED and photodiode sensor. Implements SpO2 calculation, pulse rate detection, and perfusion index.

## Business Value

Non-invasive oxygen saturation monitoring enables early detection of respiratory compromise, critical for ICU patient safety.

## Subtasks

- vp-002.01: AFE4490 sensor driver (vp-002.01)
- vp-002.02: LED drive current control (vp-002.02)
- vp-002.03: Signal acquisition via DMA (vp-002.03)
- vp-002.04: Motion artifact rejection (vp-002.04)
- vp-002.05: SpO2 calculation algorithm (vp-002.05)
- vp-002.06: Pulse rate detection (vp-002.06)
- vp-002.07: Perfusion index calculation (vp-002.07)
- vp-002.08: Sensor disconnect detection (vp-002.08)
- vp-002.09: Calibration table management (vp-002.09)
- vp-002.10: Probe type auto-detection (vp-002.10)
- vp-002.11: Low perfusion handling (vp-002.11)
- vp-002.12: SpO2 accuracy verification (vp-002.12)

## Acceptance Criteria

<!-- AC:BEGIN -->
- [ ] #1 All 12 subtasks completed
- [ ] #2 SpO2 accuracy within +/- 2% (70-100% range)
- [ ] #3 Pulse rate accuracy within +/- 3 BPM
- [ ] #4 Motion artifact rejection >80% of events
- [ ] #5 Sensor disconnect detected within 5 seconds
- [ ] #6 Meets IEC 80601-2-61 requirements
<!-- AC:END -->
