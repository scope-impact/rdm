---
id: TM-001
revision: 1
title: Traceability Matrix and Verification Status
---

# Purpose

The verification status of each **design input**, grouped under the **user
need** it traces to, generated from the system of record: the design inputs
declared in the per-context design documents (`kind: design`) reconciled against
executed Allure results. Do not edit by hand. Regenerate with:

```
uv run pytest tests/acceptance --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

{% if verification is defined %}
# Summary

| Verified | Failed | Untested | Total design inputs | Allure results |
| --- | --- | --- | --- | --- |
| {{ verification.summary.verified }} | {{ verification.summary.failed }} | {{ verification.summary.untested }} | {{ verification.summary.total }} | {{ verification.summary.results_found }} |

# Traceability matrix

{% for group in verification.groups %}
## {{ group.user_need }}

| Design Input | Status | Passed | Failed | Skipped | Verifying tests | Output |
| --- | --- | --- | --- | --- | --- | --- |
{%- for di in group.design_inputs %}
| {{ di.design_input }} | {{ di.status }} | {{ di.passed }} | {{ di.failed }} | {{ di.skipped }} | {{ di.tests|join(', ') if di.tests else '—' }} | {{ di.outputs|join(', ') if di.outputs else '—' }} |
{%- endfor %}
{% endfor %}

{% if verification.orphans %}
# Orphan test tags

Allure tags matching no declared design input:
{% for orphan in verification.orphans %}
- {{ orphan }}
{%- endfor %}
{% endif %}
{% else %}
No `verification` data was provided. Run `rdm story verify` to generate
`dhf/data/verification.yml`, then re-render this document.
{% endif %}
