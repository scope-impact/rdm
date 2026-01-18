---
id: vp-doc-001
title: "Risk Cluster: Measurement Accuracy (RC-MEAS)"
type: risk-cluster
created_date: '2026-01-18'
labels: [risk-cluster, RC-MEAS, tampering, info_disclosure]
stride_categories: [tampering, info_disclosure]
---

# Measurement Accuracy (RC-MEAS)

STRIDE Categories: Tampering, Information Disclosure

Root Risk: Inaccurate vital sign measurements leading to incorrect clinical decisions and potential patient harm.

## Affected Requirements

- [vp-002.05](../../tasks/vp-002.05%20-%20SpO2-Calculation.md)
- [vp-002.09](../../tasks/vp-002.09%20-%20Calibration-Table.md)
- [vp-002.12](../../tasks/vp-002.12%20-%20SpO2-Accuracy-Verification.md)
- [vp-003.05](../../tasks/vp-003.05%20-%20QRS-Detection.md)
- [vp-003.06](../../tasks/vp-003.06%20-%20Heart-Rate-Calculation.md)
- [vp-004.05](../../tasks/vp-004.05%20-%20BP-Calculation.md)
- [vp-004.10](../../tasks/vp-004.10%20-%20NIBP-Accuracy-Verification.md)

---

## RISK-MEAS-001: SpO2 Calibration Corruption

| Attribute | Value |
|-----------|-------|
| **STRIDE Category** | Tampering |
| **Severity** | Critical |
| **Probability** | Rare |
| **Risk Level** | Medium |

### Hazard

SpO2 calibration coefficients corrupted in non-volatile memory.

### Situation

Memory bit-flip, incomplete firmware update, or manufacturing defect corrupts calibration data.

### Harm

Inaccurate SpO2 readings; clinician makes treatment decisions based on false data; potential hypoxic injury.

### Description

SpO2 accuracy depends on empirically-derived calibration coefficients stored in non-volatile memory. Corruption could cause systematic measurement error that may not be immediately obvious to clinicians.

### Affected Requirements

- [vp-002.09](../../tasks/vp-002.09%20-%20Calibration-Table.md)
- [vp-002.05](../../tasks/vp-002.05%20-%20SpO2-Calculation.md)

### Mitigation

**Status:** Mitigated

#### Controls

- CRC verification of calibration data on boot (refs: [vp-002.09](../../tasks/vp-002.09%20-%20Calibration-Table.md):AC:5)
- Default calibration used if CRC fails with technical alarm (refs: [vp-002.09](../../tasks/vp-002.09%20-%20Calibration-Table.md):AC:6)
- Dual storage with voting (primary and backup copies)
- Manufacturing verification of calibration integrity

**Residual Risk:** Low

---

## RISK-MEAS-002: ECG QRS Detection Failure

| Attribute | Value |
|-----------|-------|
| **STRIDE Category** | Information Disclosure |
| **Severity** | Serious |
| **Probability** | Unlikely |
| **Risk Level** | Medium |

### Hazard

QRS detection algorithm fails to detect heartbeats or generates false detections.

### Situation

Low amplitude ECG, pacemaker artifacts, motion artifact, or unusual QRS morphology.

### Harm

Incorrect heart rate display; missed arrhythmia detection; false asystole alarm; alarm fatigue.

### Description

QRS detection is foundation for heart rate calculation and arrhythmia analysis. False negatives cause missed beats (low HR); false positives cause overcounting (high HR). Both can trigger inappropriate alarms or mask true conditions.

### Affected Requirements

- [vp-003.05](../../tasks/vp-003.05%20-%20QRS-Detection.md)
- [vp-003.06](../../tasks/vp-003.06%20-%20Heart-Rate-Calculation.md)

### Mitigation

**Status:** Partial

#### Controls

- Pan-Tompkins algorithm validated on MIT-BIH database (refs: [vp-003.05](../../tasks/vp-003.05%20-%20QRS-Detection.md):AC:1)
- Adaptive threshold adjusts to signal amplitude (refs: [vp-003.05](../../tasks/vp-003.05%20-%20QRS-Detection.md):AC:5)
- Pacemaker spike detection prevents double-counting (refs: [vp-003.11](../../tasks/vp-003.11%20-%20Pacemaker-Detection.md):AC:1)
- Heart rate averaging with outlier rejection (refs: [vp-003.06](../../tasks/vp-003.06%20-%20Heart-Rate-Calculation.md):AC:3)

**Residual Risk:** Low

#### Recommended Controls

- Continuous monitoring of detection confidence
- Auto-lead selection for best signal quality
- Multi-lead fusion for improved robustness

---

## RISK-MEAS-003: NIBP Measurement Error Under Motion

| Attribute | Value |
|-----------|-------|
| **STRIDE Category** | Tampering |
| **Severity** | Serious |
| **Probability** | Possible |
| **Risk Level** | Medium |

### Hazard

Patient motion during NIBP measurement corrupts oscillometric envelope.

### Situation

Patient moves arm, coughs, or is repositioned during BP measurement.

### Harm

Inaccurate BP reading displayed; clinician adjusts medication based on false value; hemodynamic instability not detected.

### Description

Oscillometric NIBP requires stable cuff pressure for accurate envelope analysis. Motion introduces artifacts that can shift the apparent maximum amplitude, causing systematic measurement error.

### Affected Requirements

- [vp-004.05](../../tasks/vp-004.05%20-%20BP-Calculation.md)
- [vp-004.06](../../tasks/vp-004.06%20-%20NIBP-Motion-Artifact.md)

### Mitigation

**Status:** Partial

#### Controls

- Accelerometer-based motion detection (refs: [vp-004.06](../../tasks/vp-004.06%20-%20NIBP-Motion-Artifact.md):AC:1)
- Automatic retry on artifact detection (refs: [vp-004.06](../../tasks/vp-004.06%20-%20NIBP-Motion-Artifact.md):AC:3)
- Pulse irregularity flagged as artifact (refs: [vp-004.06](../../tasks/vp-004.06%20-%20NIBP-Motion-Artifact.md):AC:2)
- Motion indicator displayed during measurement (refs: [vp-004.06](../../tasks/vp-004.06%20-%20NIBP-Motion-Artifact.md):AC:5)

**Residual Risk:** Medium

#### Recommended Controls

- Real-time motion quality indicator
- Clinician training on motion impact
- Consider continuous BP trend with arterial line for unstable patients

---

## RISK-MEAS-004: Algorithm Drift from Reference Standard

| Attribute | Value |
|-----------|-------|
| **STRIDE Category** | Tampering |
| **Severity** | Serious |
| **Probability** | Rare |
| **Risk Level** | Low |

### Hazard

Measurement algorithms deviate from validated reference accuracy over device lifetime.

### Situation

Sensor aging, component drift, or environmental stress causes systematic measurement shift.

### Harm

Gradual accuracy degradation may not trigger alarms but affects treatment decisions over time.

### Description

All analog components drift over time and environmental exposure. Without periodic verification, measurement accuracy may degrade below specification without obvious indication to clinicians.

### Affected Requirements

- [vp-002.12](../../tasks/vp-002.12%20-%20SpO2-Accuracy-Verification.md)
- [vp-004.10](../../tasks/vp-004.10%20-%20NIBP-Accuracy-Verification.md)

### Mitigation

**Status:** Partial

#### Controls

- Factory calibration with traceability (refs: [vp-002.09](../../tasks/vp-002.09%20-%20Calibration-Table.md):AC:4)
- Self-test on boot verifies sensor response (refs: [vp-001.06](../../tasks/vp-001.06%20-%20Boot-Sequence-Self-Test.md):AC:1)
- NIBP pressure transducer zero calibration on startup (refs: [vp-004.01](../../tasks/vp-004.01%20-%20Pressure-Transducer-Driver.md):AC:6)

**Residual Risk:** Low

#### Recommended Controls

- Recommended annual calibration verification
- Trend monitoring for systematic drift
- Comparison with external reference (biomedical engineering check)
