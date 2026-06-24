---
id: DR-001
revision: 1
title: Design Review
---

# Purpose

This document records the **design review(s)** for {{ device.name }} software. A design review is a documented, comprehensive, and systematic examination of the design to evaluate the adequacy of the design inputs and the design's ability to meet them, and to identify problems.

A design review of the design inputs (see the Design Input document) must be conducted and approved *before* design work transitions into backlog/implementation tasks.

[[Design reviews satisfy ISO 13485:2016 7.3.5 and 21 CFR 820.30(e). Each review must include participants who do not have direct responsibility for the design stage being reviewed, plus any needed specialists. Results, participants, and dates must be documented.]]

# Scope

This document applies to {{ device.name }} release {{ device.version }}.

# Review Records

TODO: Record each design review below. Add one subsection per review. A review is not complete until its disposition is recorded as **Approved** and all action items are resolved or tracked.

ENDTODO

## Design Review 1 — Design Inputs

**Date:** TODO

**Design stage reviewed:** Design Inputs (see Design Input, DI-001)

**Disposition:** TODO (Approved / Approved with actions / Rejected)

### Participants

[[At least one reviewer must not have direct responsibility for the design stage under review (21 CFR 820.30(e)).]]

| Name | Role | Independent of design stage? |
| --- | --- | --- |
{%- for person in people %}
| {{ person.name }} | {{ person.roles|join(', ') }} | TODO |
{%- endfor %}

### Items Reviewed

TODO: Confirm each item was examined during the review. Replace with review-specific notes and evidence.

- Design inputs are complete, unambiguous, and verifiable.
- Design inputs do not conflict with one another.
- Each design input traces to a user need captured in the Software Design Specification (SDD), and its acceptance criteria are captured as Allure tags on the verifying tests (these are the sources of truth).
- Risks associated with the design inputs have been considered ({{ workflow.risk_management_file }}).
- Open issues and action items have been captured below.

ENDTODO

### Findings and Action Items

| ID | Finding | Owner | Due | Status |
| --- | --- | --- | --- | --- |
| TODO | TODO | TODO | TODO | Open |

### Approval

[[Record the reviewers' approval. The transition of design inputs into backlog/implementation tasks is gated on this approval.]]

| Name | Role | Date | Approved |
| --- | --- | --- | --- |
{%- for person in people %}
| {{ person.name }} | {{ person.roles|join(', ') }} | TODO | [ ] |
{%- endfor %}
