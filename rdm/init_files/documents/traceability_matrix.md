---
id: TM-001
revision: 1
title: Traceability Matrix and Verification Status
---

# Purpose

This document presents the verification status of each **design input** for {{ device.name }}, grouped under the **user need** it traces to: design inputs are verified (§820.30(f): output meets input) by the automated tests tagged with their ID; user needs are validated separately.

It is **generated** from the system of record: the design inputs declared in the per-context design documents (`kind: design`), the user-need registry (V&V plan), and the executed Allure test results. Do not edit the matrix by hand. Regenerate it with:

```
rdm story verify --dhf <dhf> --allure-results <allure-results-dir> -o data/verification.yml
```

then re-render this document.

{% if verification is defined %}
# Summary

| Verified | Failed | Untested | Total design inputs | Allure results |
| --- | --- | --- | --- | --- |
| {{ verification.summary.verified }} | {{ verification.summary.failed }} | {{ verification.summary.untested }} | {{ verification.summary.total }} | {{ verification.summary.results_found }} |

# Traceability Matrix

[[Each design input traces up to the user need it refines and down to the automated test(s) that verify it; status reflects executed Allure results (ISO 13485:2016 7.3.6, 21 CFR 820.30(f)).]]

{% for group in verification.groups %}
## {{ group.user_need }}

| Design Input | Status | Passed | Failed | Skipped | Verifying Tests | Output |
| --- | --- | --- | --- | --- | --- | --- |
{%- for di in group.design_inputs %}
| {{ di.design_input }} | {{ di.status }} | {{ di.passed }} | {{ di.failed }} | {{ di.skipped }} | {{ di.tests|join(', ') if di.tests else '—' }} | {{ di.outputs|join(', ') if di.outputs else '—' }} |
{%- endfor %}
{% endfor %}

{% if verification.orphans %}
# Orphan Test Tags

The following Allure tags reference no declared design input; either declare the design input in a `kind: design` document or correct the tag:

{% for orphan in verification.orphans %}
- {{ orphan }}
{%- endfor %}
{% endif %}
{% else %}
TODO: No `verification` data found. Run `rdm story verify` to generate `data/verification.yml`, then re-render to populate the traceability matrix and verification status.
ENDTODO
{% endif %}
