---
id: vp-001
title: "Firmware Architecture and RTOS Foundation"
status: In Progress
created_date: '2026-01-18'
labels: [vp-001, EP-001, firmware, rtos, architecture]
milestone: m-1
priority: critical
---

## Description

FreeRTOS-based firmware architecture for STM32H7 MCU. Establishes task scheduling, inter-task communication, memory management, and watchdog supervision.

## Business Value

Provides deterministic real-time response for safety-critical vital sign monitoring. Ensures reliable operation under all conditions.

## Subtasks

- vp-001.01: FreeRTOS kernel configuration (vp-001.01)
- vp-001.02: Task priority scheme and scheduling (vp-001.02)
- vp-001.03: Inter-task messaging (queues, semaphores) (vp-001.03)
- vp-001.04: Memory pool allocator (vp-001.04)
- vp-001.05: Watchdog and fault recovery (vp-001.05)
- vp-001.06: Boot sequence and self-test (vp-001.06)
- vp-001.07: Low-power mode management (vp-001.07)
- vp-001.08: Debug and trace infrastructure (vp-001.08)

## Acceptance Criteria

<!-- AC:BEGIN -->
- [ ] #1 All 8 subtasks completed
- [ ] #2 FreeRTOS kernel boots within 500ms
- [ ] #3 Watchdog recovers from task starvation
- [ ] #4 Memory pool prevents fragmentation
- [ ] #5 Unit tests achieve >90% coverage
<!-- AC:END -->
