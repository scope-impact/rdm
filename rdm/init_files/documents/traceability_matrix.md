---
id: TM-001
revision: 1
title: Traceability Matrix and Verification Status
---

# Purpose

This document presents the verification status of each user need for {{ device.name }}, traced from the user needs declared in the Software Design Specification to the automated tests that verify them.

It is **generated** from the system of record: the SDD `user_needs` and the executed Allure test results. Do not edit the matrix by hand. Regenerate it with:

```
rdm story verify --dhf <dhf> --allure-results <allure-results-dir> -o data/verification.yml
```

then re-render this document.

{% if verification is defined %}
# Summary

| Verified | Failed | Untested | Total | Allure results |
| --- | --- | --- | --- | --- |
| {{ verification.summary.verified }} | {{ verification.summary.failed }} | {{ verification.summary.untested }} | {{ verification.summary.total }} | {{ verification.summary.results_found }} |

# Traceability Matrix

[[Each user need traces to the automated test(s) that verify its acceptance criteria; status reflects executed Allure results (ISO 13485:2016 7.3.6, 21 CFR 820.30(f)).]]

| User Need | Status | Passed | Failed | Skipped | Verifying Tests |
| --- | --- | --- | --- | --- | --- |
{%- for need in verification.needs %}
| {{ need.user_need }} | {{ need.status }} | {{ need.passed }} | {{ need.failed }} | {{ need.skipped }} | {{ need.tests|join(', ') if need.tests else '—' }} |
{%- endfor %}

{% if verification.orphans %}
# Orphan Test Tags

The following Allure tags reference no declared user need; either declare the user need in the SDD or correct the tag:

{% for orphan in verification.orphans %}
- {{ orphan }}
{%- endfor %}
{% endif %}
{% else %}
TODO: No `verification` data found. Run `rdm story verify` to generate `data/verification.yml`, then re-render to populate the traceability matrix and verification status.
ENDTODO
{% endif %}
