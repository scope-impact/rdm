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
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

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
No `verification` data was provided. Run `rdm story verify` to generate
`dhf/data/verification.yml`, then re-render this document.
{% endif %}
