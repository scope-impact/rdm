---
title: Risk Management Report
subtitle: VitalPulse Patient Monitoring System
document-id: RMR-001
standard: ISO 14971:2019
---

## Risk Summary
{% set risk_summary = query("SELECT COUNT(*) as total, risk_level FROM risks GROUP BY risk_level") %}
| Risk Level | Count |
|------------|-------|
{% for r in risk_summary -%}
| {{ r.risk_level or 'Unassessed' }} | {{ r.total }} |
{% endfor %}

## Risk Register
{% set risks = query("SELECT * FROM risks ORDER BY local_id") %}
{% for risk in risks %}
### {{ risk.title }}

| Attribute | Value |
|-----------|-------|
| **STRIDE Category** | {{ risk.stride_category or 'N/A' }} |
| **Severity** | {{ risk.severity or 'N/A' }} |
| **Probability** | {{ risk.probability or 'N/A' }} |
| **Risk Level** | {{ risk.risk_level or 'N/A' }} |
| **Mitigation Status** | {{ risk.mitigation_status or 'Open' }} |
| **Residual Risk** | {{ risk.residual_risk or 'N/A' }} |
{% if risk.hazard -%}
**Hazard:** {{ risk.hazard }}
{% endif -%}
{% if risk.situation -%}
**Situation:** {{ risk.situation }}
{% endif -%}
{% if risk.harm -%}
**Harm:** {{ risk.harm }}
{% endif -%}
{% if risk.description -%}
**Description:** {{ risk.description }}
{% endif %}
#### Controls
{% set controls = query("SELECT * FROM risk_controls WHERE risk_id = '" ~ risk.global_id ~ "' ORDER BY sort_order") %}
{% for ctrl in controls -%}
- {{ ctrl.description }}{% if ctrl.refs %} (refs: {{ ctrl.refs }}){% endif %}

{% endfor %}
#### Affected Requirements
{% set affected = query("SELECT requirement_id FROM risk_requirements WHERE risk_id = '" ~ risk.global_id ~ "'") %}
{% for req in affected -%}
- {{ req.requirement_id }}
{% endfor %}
---
{% endfor %}

## Risk Acceptance

All risks with residual risk level of "Low" or "Medium" are accepted per the risk management plan.
