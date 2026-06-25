---
id: DI-001
revision: 1
title: Design Input
# Design-input registry: the machine-readable verification anchor. Each entry is
# verified by a test tagged @allure.story("<id>") and traces up to the user
# need(s) it refines (declared in the V&V plan `user_needs`). Replace the example.
design_inputs:
  - id: DI-1
    text: "TODO: state a verifiable requirement the design must satisfy."
    traces_to: [UN-001]
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

A **design input** refines one or more **user needs** (validated) and is itself **verified** (§820.30(f): output meets input) by the automated test(s) tagged with its ID. The traceability **sources of truth** for {{ device.name }} are:

- The **design-input registry** (the `design_inputs` frontmatter above) declares each design input and the user need(s) it `traces_to`.
- The **user-need registry** (the V&V plan `user_needs`) is the validation anchor.
- An **Allure tag** `@allure.story("<DI-id>")` on the verifying test links each design input to its executed evidence; the test *is* the acceptance criterion ("live BDD" — no separate Gherkin spec).

> **User Need (validated) → Design Input (`traces_to`) → verifying test (`@allure.story`) → executed result.**

[[Because the registries and the Allure tags are the source of truth, design inputs remain traceable forward into the design and its verification, and backward from each test to the design input it satisfies (ISO 13485:2016 7.3.3, 21 CFR 820.30(c) and (j)). The generated traceability matrix reconciles the registry against executed results; backlog tasks may mirror this for planning, but they are not the system of record.]]

# Approval

Approval of these design inputs is recorded in **version control**, not in this document. The pull request in which this revision was reviewed and merged is the approval record of truth: the PR approver(s) constitute the sign-off, the merge commit fixes the date, and the commit author identifies authorship. Merge review is enforced by the project's `reviews_required` policy.

[[Approval is captured once, in the controlled version-control history, to avoid a duplicate and potentially conflicting sign-off record (21 CFR 820.30(c) review/approval; records per 820.180). Reviewer independence (820.30(e)) is evidenced by the PR being approved by someone other than the commit author. Do not re-record approvals here.]]
