---
id: TM-001
revision: 1
title: Traceability Matrix and Verification Status
---

# Purpose

This document presents the verification status of each **requirement** of {{ device.name }}'s spec (a stamped scenario, `item-NN-SSS`), grouped under the **user need** it addresses: requirements are verified (§820.30(f): output meets input) by the automated tests tagged with their id; user needs are validated separately.

It is **generated** from the system of record: the spec (stamped Gherkin scenarios, the Allium surfaces and the event model) and the executed Allure test results. Do not edit the matrix by hand. Regenerate it with:

```
rdm story verify --dhf <dhf> --allure-results <allure-results-dir> -o data/verification.yml
```

then re-render this document.

{% if verification is defined %}
# Summary

| Verified | Failed | Untested | Total requirements | Allure results |
| --- | --- | --- | --- | --- |
| {{ verification.summary.verified }} | {{ verification.summary.failed }} | {{ verification.summary.untested }} | {{ verification.summary.total }} | {{ verification.summary.results_found }} |

# Traceability matrix

Each user need, with the requirements (stamped scenarios of the spec) that
address it. Runs: passed / failed / skipped.

{% for group in verification.groups %}
## {{ group.user_need }}

{% if group.text %}{{ group.text }}{% endif %}{% if group.actors %} Facing: {{ group.actors|join(', ') }}.{% endif %}

| Requirement | Scenario | Status | Runs | Verifying tests |
| ------------ | ------------------------------------ | ------------ | ---------- | ------------------------------------ |
{%- for r in group.requirements %}
| {{ r.requirement }} | {{ r.text }} | {{ r.status }} | {{ r.passed }}/{{ r.failed }}/{{ r.skipped }} | {{ r.tests|join(', ') if r.tests else '—' }} |
{%- endfor %}
{% endfor %}

{% if verification.orphans %}
# Orphan test tags

Allure tags matching no requirement:
{% for orphan in verification.orphans %}
- {{ orphan }}
{%- endfor %}
{% endif %}
{% else %}
TODO: No `verification` data found. Run `rdm story verify` to generate `data/verification.yml`, then re-render to populate the traceability matrix and verification status.
ENDTODO
{% endif %}
