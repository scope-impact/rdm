---
id: DI-001
revision: 1
title: Design Input — VitalView
# Design-input registry (per ADR 0001): the verification anchor. Each design
# input refines the user need(s) it `traces_to` (validated, in the V&V plan) and
# is verified (§820.30(f): output meets input) by the test tagged
# @allure.story("<id>"). The test IS the acceptance criterion ("live BDD").
design_inputs:
  - id: DI-1
    text: "VitalView shall raise a prioritised alert within the specified latency when ingested vitals breach a clinician-configured threshold."
    traces_to: [UN-001]
  - id: DI-2
    text: "VitalView shall record clinician acknowledgement of an alert, with author and timestamp."
    traces_to: [UN-001]
  - id: DI-3
    text: "VitalView shall let an authenticated clinician view a patient's current and recent vital signs."
    traces_to: [UN-002]
  - id: DI-4
    text: "VitalView shall restrict patient-data access to authorized clinicians and write an audit record for each access."
    traces_to: [UN-003]
---

# Purpose

Captures the **design inputs** for VitalView — the verifiable requirements the
design must meet. Each refines a user need (the *what*, validated; declared in
the V&V plan) and is verified by an automated test.

# Design inputs

The user needs (validated) live in `verification_and_validation_plan.md`. The
design inputs below are the verification anchor; each is realised by one or more
bounded-context design outputs (the SDDs) and verified by the test tagged with
its ID:

- **DI-1 (alert latency)** — refines UN-001; realised by the **alerting** SDD
  (`SDS-ALERT-001`). Verified by `@allure.story("DI-1")`.
- **DI-2 (alert acknowledgement)** — refines UN-001; realised by the **alerting**
  SDD. Verified by `@allure.story("DI-2")`.
- **DI-3 (view vitals)** — refines UN-002; realised by the **dashboard** and
  **auth** SDDs (`SDS-DASH-001`, `SDS-AUTH-001`). Verified by
  `@allure.story("DI-3")`.
- **DI-4 (access control + audit)** — refines UN-003; realised by the **auth**
  SDD (`SDS-AUTH-001`). Verified by `@allure.story("DI-4")`.

# Traceability

> **User Need (validated) → Design Input (`traces_to`) → verifying test
> (`@allure.story`) → executed result.**

The generated traceability matrix reconciles this registry against executed
Allure results. There is no separate Gherkin spec: the test *is* the acceptance
criterion ("live BDD").

# Approval

Approval of these design inputs is recorded in version control (the reviewed PR
that merged this revision), not duplicated here.
