---
id: TM-001
revision: 1
title: Traceability Matrix and Verification Status
---

# Purpose

The verification status of each RDM user need, generated from the system of
record: the user-need registry (the V&V plan) reconciled against executed Allure
results. Do not edit by hand. Regenerate with:

```
uv run pytest tests/acceptance --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

{% if verification is defined %}
# Summary

| Verified | Failed | Untested | Total | Allure results |
| --- | --- | --- | --- | --- |
| {{ verification.summary.verified }} | {{ verification.summary.failed }} | {{ verification.summary.untested }} | {{ verification.summary.total }} | {{ verification.summary.results_found }} |

# Traceability matrix

| User Need | Status | Passed | Failed | Skipped | Verifying tests |
| --- | --- | --- | --- | --- | --- |
{%- for need in verification.needs %}
| {{ need.user_need }} | {{ need.status }} | {{ need.passed }} | {{ need.failed }} | {{ need.skipped }} | {{ need.tests|join(', ') if need.tests else '—' }} |
{%- endfor %}

{% if verification.orphans %}
# Orphan test tags

Allure tags matching no registered user need:
{% for orphan in verification.orphans %}
- {{ orphan }}
{%- endfor %}
{% endif %}
{% else %}
No `verification` data was provided. Run `rdm story verify` to generate
`dhf/data/verification.yml`, then re-render this document.
{% endif %}
