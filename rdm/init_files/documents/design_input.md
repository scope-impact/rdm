---
id: DI-001
revision: 1
title: Design Input
---

# Purpose

This document captures the **design inputs** for {{ device.name }} software. Design inputs are the physical and performance requirements of the device that are used as the basis for device design.

Design inputs must be established, reviewed, and approved *before* design work transitions into implementation tasks. This document, together with the {{ workflow.software_requirements_location }}, forms the agreed-upon basis against which the design is reviewed and verified.

[[Design inputs satisfy ISO 13485:2016 7.3.3 and 21 CFR 820.30(c). They must be reviewed for adequacy and approved per 21 CFR 820.30(c) before becoming the basis for the design.]]

# Scope

This document applies to {{ device.name }} release {{ device.version }}.

# Definitions

A **design input** is a requirement, constraint, or characteristic that the design must satisfy. Design inputs are the *what*; the design output (see the Software Design Specification) is the *how*.

A **design review** is a documented, systematic examination of the design to evaluate its ability to meet the design inputs (see the Design Review document).

# Sources of Design Inputs

TODO: Describe where the design inputs come from. Design inputs are typically derived from, and traceable to:

- User needs ({{ workflow.user_needs_location }}),
- System requirements ({{ workflow.system_requirements_location }}),
- The risk management file ({{ workflow.risk_management_file }}),
- Applicable regulatory and standards requirements,
- Intended use and use environment.

Remove any sources that do not apply to this device.

ENDTODO

# Design Inputs

The table below enumerates the design inputs for {{ device.name }}. Each design input is traceable to one or more software requirements so that the design can be verified against it.

{% if requirements %}
| Design Input (Soft. Req. ID) | Type | Title | Source |
| --- | --- | --- | --- |
{%- for requirement in requirements %}
| {{ requirement.id }} | {{ requirement.type }} | {{ requirement.title }} | {{ requirement.system_requirements|join(', ') if requirement.system_requirements else 'TODO' }} |
{%- endfor %}
{% else %}
TODO: No requirements found. Define software requirements in `data/requirements.yml` so that they appear here as design inputs.
{% endif %}

# Acceptance Criteria for Design Inputs

[[Design inputs must be unambiguous, verifiable, and not in conflict with each other (21 CFR 820.30(c)).]]

TODO: Confirm and record that the design inputs above satisfy each of the following. Replace this list with project-specific evidence or notes.

- Each design input is unambiguous.
- Each design input is verifiable or measurable.
- Design inputs do not conflict with one another.
- Incomplete, ambiguous, or conflicting requirements have been resolved with the responsible parties.

ENDTODO

# Traceability

The output of a design input is a **user need**. For {{ device.name }} the traceability **sources of truth** are:

- The **Software Design Specification (SDD)** captures each **user need**.
- An **Allure tag** on the corresponding automated test(s) captures each **acceptance criterion (AC)**.

> **Design Input → User Need (captured in the SDD) → Acceptance Criteria (captured as Allure tags on the verifying tests).**

[[Because the SDD and the Allure tags are the source of truth, design inputs remain traceable forward into the design and its verification, and backward from each test to the design input it satisfies (ISO 13485:2016 7.3.3, 21 CFR 820.30(c) and (j)). Backlog tasks may mirror this information for planning, but they are not the system of record.]]

TODO: Maintain the mapping below from each design input to the user need recorded in the SDD and the Allure tag(s) that verify its acceptance criteria.

| Design Input | User Need (SDD reference) | Acceptance Criteria (Allure tag) |
| --- | --- | --- |
| TODO | TODO | TODO |

ENDTODO

# Approval

[[The design inputs must be reviewed and approved before they are used as the basis for the design (21 CFR 820.30(c)). Record the approval below. Approval of this document is a precondition for transitioning work into backlog/implementation tasks.]]

TODO: Record the approval of these design inputs. Each approver should confirm that the design inputs are adequate and appropriate.

| Name | Role | Date | Approved |
| --- | --- | --- | --- |
{%- for person in people %}
| {{ person.name }} | {{ person.roles|join(', ') }} | TODO | [ ] |
{%- endfor %}

ENDTODO
