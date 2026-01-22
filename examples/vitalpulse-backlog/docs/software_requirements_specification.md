---
title: Software Requirements Specification
subtitle: VitalPulse Patient Monitoring System
document-id: SRS-001
classification: IEC 62304 Class C
---

## Purpose

This document specifies the software requirements for the VitalPulse Patient Monitoring System.

## Scope
{% set milestones = query("SELECT * FROM milestones ORDER BY local_id") %}
{% for m in milestones -%}
- **{{ m.local_id }}**: {{ m.title }}
{% endfor %}

## Requirements
{% set features = query("SELECT * FROM tasks WHERE milestone_id IS NOT NULL ORDER BY local_id") %}
{% for feature in features %}
### {{ feature.local_id }}: {{ feature.title }}

{{ feature.description }}

**Priority:** {{ feature.priority or 'medium' }}
**Status:** {{ feature.status }}

#### Acceptance Criteria
{% set criteria = query("SELECT * FROM acceptance_criteria WHERE task_id = '" ~ feature.global_id ~ "' ORDER BY sort_order") %}
{% for ac in criteria -%}
- **AC-{{ ac.number }}**: {{ ac.text }}{% if ac.completed %} [VERIFIED]{% endif %}

{% endfor %}
#### Sub-Requirements
{% set subtasks = query("SELECT * FROM subtasks WHERE parent_task_id = '" ~ feature.global_id ~ "' ORDER BY local_id") %}
{% for sub in subtasks %}
**{{ sub.local_id }}**: {{ sub.title }}
{% set sub_criteria = query("SELECT * FROM acceptance_criteria WHERE task_id = '" ~ sub.global_id ~ "' ORDER BY sort_order") %}
{% for ac in sub_criteria -%}
- AC-{{ ac.number }}: {{ ac.text }}
{% endfor %}
{% endfor %}
---
{% endfor %}

## Traceability

| Requirement | Status | Acceptance Criteria |
|-------------|--------|---------------------|
{% for feature in features -%}
| {{ feature.local_id }} | {{ feature.status }} | {{ feature.acceptance_criteria_count }} |
{% endfor %}
