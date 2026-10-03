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

| Verified | Failed | Untested | Total design inputs | Allure results |
| --- | --- | --- | --- | --- |
| {{ verification.summary.verified }} | {{ verification.summary.failed }} | {{ verification.summary.untested }} | {{ verification.summary.total }} | {{ verification.summary.results_found }} |

# Traceability matrix

Runs: passed / failed / skipped.

{% for group in verification.groups %}
## {{ group.user_need }}

| Input | Status | Runs | Verifying tests | Output |
| ------------ | ------------ | ---------- | ------------------------------------ | ---------------------------- |
{%- for di in group.design_inputs %}
| {{ di.design_input }} | {{ di.status }} | {{ di.passed }}/{{ di.failed }}/{{ di.skipped }} | {{ di.tests|join(', ') if di.tests else '—' }} | {{ di.outputs|join(', ') if di.outputs else '—' }} |
{%- endfor %}
{% endfor %}

{% if verification.orphans %}
# Orphan test tags

Allure tags matching no declared design input:
{% for orphan in verification.orphans %}
- {{ orphan }}
{%- endfor %}
{% endif %}

{% if verification.unit_coverage is defined %}
# Unit-test code coverage

How much of each component's code its unit tests ran, from the unit tests' coverage report: evidence of unit verification (IEC 62304 5.5), shown beside the design inputs, not inside them. A design input is verified by its acceptance test, never by coverage.

| Component | Context | Lines run | Lines measured | Coverage |
| --- | --- | --- | --- | --- |
{%- for c in verification.unit_coverage.components %}
| {{ c.name }} | {{ c.context or '—' }} | {{ c.executed }} | {{ c.measured }} | {{ c.percent }}% |
{%- endfor %}
{% if verification.unit_coverage.unmeasured %}

Components whose code the report does not measure:
{% for c in verification.unit_coverage.unmeasured %}
- {{ c.name }} ({{ c.context or '—' }})
{%- endfor %}
{% endif %}
{% endif %}
{% else %}
No `verification` data was provided. Run `rdm story verify` to generate
`dhf/data/verification.yml`, then re-render this document.
{% endif %}
