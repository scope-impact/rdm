---
id: m-1
title: "Core Sensor Platform"
status: in_progress
created_date: '2026-01-18'
labels: [EP-001, sensors, firmware, foundation]
---

## Description

EP-001: Core sensor acquisition and signal processing platform for VitalPulse Patient Monitoring System. Establishes the foundational firmware architecture and vital sign measurement capabilities for IEC 62304 Class C medical device.

### Scope

- RTOS-based firmware architecture (FreeRTOS on STM32H7)
- Sensor interface drivers (SpO2, ECG, NIBP, Temperature)
- Signal acquisition pipeline with DMA
- Real-time signal processing (filtering, artifact detection)
- Calibration and sensor validation
- Power management for battery operation

### Regulatory Context

- IEC 62304 Class C software safety classification
- IEC 60601-1 medical electrical equipment
- IEC 80601-2-61 (SpO2), IEC 60601-2-27 (ECG), IEC 80601-2-30 (NIBP)

## Business Value

Provides accurate, reliable vital sign measurements that clinicians can trust for patient care decisions. Foundation for all monitoring features required for ICU patient safety.

## Features

- vp-001: Firmware Architecture and RTOS Foundation (8 stories)
- vp-002: SpO2 Pulse Oximetry Module (12 stories)
- vp-003: ECG Electrocardiogram Module (15 stories)
- vp-004: NIBP Blood Pressure Module (10 stories)
