---
id: decision-001
title: "ADR-001: FreeRTOS for Real-Time Operating System"
date: '2026-01-18'
status: accepted
labels: [firmware, rtos, architecture, safety]
---

## Context

VitalPulse Patient Monitoring System requires a real-time operating system to manage concurrent tasks including sensor acquisition, signal processing, alarm evaluation, display rendering, and communication. The RTOS must support IEC 62304 Class C medical device development with deterministic timing guarantees for safety-critical functions.

## Decision

Use FreeRTOS v10.5+ as the real-time operating system on STM32H7 MCU.

## Rationale

- **Medical Device Heritage:** FreeRTOS is widely used in FDA-cleared and CE-marked medical devices
- **IEC 62304 Support:** FreeRTOS SafeRTOS variant available for highest safety certification
- **Deterministic Timing:** Configurable tick rate, priority-based preemptive scheduling
- **Memory Protection:** MPU support for memory isolation between tasks
- **Vendor Support:** Native STM32 integration via STM32Cube ecosystem
- **Community:** Large community, extensive documentation, long-term support
- **Cost:** Open source (MIT license) with commercial SafeRTOS option

## Alternatives Considered

| Alternative | Pros | Cons |
|-------------|------|------|
| Zephyr RTOS | Modern, good hardware abstraction | Less medical device heritage |
| ThreadX | IEC 62304 certified | Higher licensing cost |
| Bare metal | Simplest, fastest | No task isolation, harder to maintain |
| Linux RT | Feature-rich | Overkill for embedded, boot time |

## Trade-offs

- FreeRTOS requires careful configuration for IEC 62304 Class C
- SafeRTOS adds cost but provides pre-certified kernel
- Standard FreeRTOS sufficient with documented verification

## Consequences

**Positive:**
- Proven solution for medical devices
- Extensive tooling (Tracealyzer, SystemView)
- Easy to hire developers with FreeRTOS experience
- Future path to SafeRTOS if certification requires

**Negative:**
- Documentation burden for IEC 62304 Class C
- Must implement SOUP qualification procedures
- Some features require careful configuration for safety

## References

- [vp-001](../tasks/vp-001%20-%20Firmware-Architecture-RTOS.md): Firmware Architecture and RTOS Foundation
- [vp-001.01](../tasks/vp-001.01%20-%20FreeRTOS-Kernel-Config.md): FreeRTOS Kernel Configuration
