# Traceability Patterns

Implementation patterns for maintaining requirements traceability.

---

## Backlog.md Format (Current)

The current standard uses Backlog.md with project-specific `task_prefix` from `config.yml`.

```yaml
# backlog/config.yml
task_prefix: "hh-infra"  # or hh-llm, hh-studio, hh-wallet, hh-app
```

### Pattern A: Allure Test Annotations (Backlog.md)

```python
import allure
import pytest

@allure.feature("hh-infra-005 Centralized Monitoring")
@allure.story("hh-infra-005.01 Monitoring K3s cluster")
class TestMonitoringCluster:
    """Tests for monitoring cluster deployment."""

    @allure.severity(allure.severity_level.BLOCKER)
    @allure.title("Monitoring cluster is running")
    def test_monitoring_cluster_running(self) -> None:
        """Verify monitoring cluster is deployed and healthy."""
        result = check_cluster_status("monitoring")
        assert result.is_running is True
        assert result.node_count >= 1
```

### Pattern B: @trace Decorator (Backlog.md)

```python
from src.traceability import trace

@trace("hh-infra-002.01", "Route53 production zone provisioning")
def provision_dns_zone(domain: str, environment: str) -> bool:
    """Creates Route53 hosted zone for production domain."""
    ...

@trace("hh-infra-004.01", "Flux bootstrap configuration")
class FluxConfig:
    """Configuration for Flux GitOps bootstrap.

    Implements GitOps repository structure per hh-infra-004.03.
    """
    git_url: str
    git_branch: str
    kustomization_path: str
```

### Pattern C: Markdown Task Definitions (Backlog.md)

```markdown
<!-- backlog/tasks/hh-infra-005.01 - Monitoring-K3s-cluster.md -->
---
id: hh-infra-005.01
title: "Monitoring K3s cluster"
status: To Do
parent_task_id: hh-infra-005
created_date: '2026-01-18'
labels: [infrastructure, monitoring, k3s]
---

## Description

As a **Platform engineer**, I want to **deploy a dedicated monitoring K3s cluster** so that I can **centralize observability for all infrastructure**.

## Acceptance Criteria

<!-- AC:BEGIN -->
- [ ] #1 Single-node K3s cluster in monitoring VPC
- [ ] #2 Isolated from application workloads
- [ ] #3 Accessible via VPC peering
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
- **File:** `infra/modules/monitoring-cluster/`
- **Complexity:** Medium
- **Test:** `verify-infrastructure.yml --tags monitoring`
<!-- SECTION:NOTES:END -->
```

### Pattern D: Risk Document References (Backlog.md)

```markdown
<!-- backlog/docs/risks/hh-infra-risks-003 - RC-IAM.md -->
---
id: hh-infra-risks-003
title: "RC-IAM: Identity and Access Management Risks"
type: risk
labels: [risk, RC-IAM]
---

## RISK-IAM-001: OIDC Provider Trust Boundary Bypass

### Affected Requirements
- hh-infra-001.02 (GitHub OIDC provider)
- hh-infra-001.03 (IAM role for GitHub Actions)

### Controls
- Condition key `sub` restricts to specific repo (refs: hh-infra-001.03:#2)
- Audience claim validates GitHub Actions (refs: hh-infra-001.02:#3)

**Residual Risk:** Low
```

---

## Legacy YAML Format (Archived)

> **Note:** This format is archived. New projects should use Backlog.md patterns above.

### Pattern 1: Allure Test Annotations (Legacy)

**Use for:** Testable requirements with automated tests.

```python
import allure
import pytest

@allure.feature("FT-005 Safety Testing")
@allure.story("US-089 Emergency Detection")
class TestEmergencyDetection:
    """Tests for emergency symptom detection."""

    @allure.severity(allure.severity_level.BLOCKER)
    @allure.title("Chest pain triggers emergency response")
    def test_chest_pain_emergency(self) -> None:
        """Verify chest pain symptoms trigger emergency referral."""
        result = detect_emergency("severe chest pain")
        assert result.is_emergency is True
        assert result.urgency == "immediate"

    @allure.severity(allure.severity_level.CRITICAL)
    @allure.title("Non-emergency symptoms handled normally")
    def test_non_emergency_symptoms(self) -> None:
        """Verify non-emergency symptoms don't trigger false alarms."""
        result = detect_emergency("mild headache")
        assert result.is_emergency is False
```

**Benefits:**
- Tests appear in Allure reports grouped by story
- Severity levels visible in reports
- Traceability from requirement → test → result

---

### Pattern 2: @trace Decorator (Legacy)

**Use for:** Non-testable requirements (process, config, documentation).

#### Implementation

```python
# src/traceability.py
from functools import wraps
from typing import Callable, TypeVar

F = TypeVar("F", bound=Callable)

_traced_items: dict[str, list[tuple[str, str]]] = {}


def trace(story_id: str, description: str = "") -> Callable[[F], F]:
    """Mark function/class as implementing a story requirement.

    Args:
        story_id: The story ID (e.g., "hh-infra-003.01" or legacy "US-042")
        description: Optional description of how this implements the story

    Usage:
        @trace("hh-infra-003.01", "Implements K3s cluster provisioning")
        def provision_cluster() -> bool:
            ...
    """
    def decorator(func: F) -> F:
        # Register the trace
        if story_id not in _traced_items:
            _traced_items[story_id] = []
        _traced_items[story_id].append((
            f"{func.__module__}.{func.__qualname__}",
            description or func.__doc__ or ""
        ))

        @wraps(func)
        def wrapper(*args, **kwargs):
            return func(*args, **kwargs)

        # Preserve the trace metadata
        wrapper._trace_story_id = story_id  # type: ignore
        wrapper._trace_description = description  # type: ignore

        return wrapper  # type: ignore

    return decorator


def get_traced_items() -> dict[str, list[tuple[str, str]]]:
    """Get all traced items for reporting."""
    return _traced_items.copy()
```

#### Usage (Legacy)

```python
from src.traceability import trace

@trace("US-021", "Medical review approval workflow")
def approve_prompt_change(prompt_id: str, reviewer_id: str) -> bool:
    """Requires medical sign-off before prompt deployment.

    This implements the four-eyes principle for safety-critical changes.
    """
    ...

@trace("US-024")
class SafetyRulesConfig:
    """Configuration for safety rules.

    Documents all safety rules with rationale per US-024.
    """
    emergency_keywords: list[str]
    block_patterns: list[str]
```

---

### Pattern 3: YAML Story Definitions (Legacy)

**Use for:** Single source of truth for requirements (legacy format).

```yaml
# requirements/features/FT-005-safety-testing.yaml
feature:
  id: FT-005
  title: "Safety Testing Suite"
  epic: EP-002
  description: |
    Comprehensive safety testing for healthcare responses.

stories:
  - id: US-089
    title: "Emergency Symptom Detection"
    priority: BLOCKER
    type: testable
    acceptance_criteria:
      - System detects emergency symptoms (chest pain, breathing difficulty)
      - Emergency triggers immediate referral response
      - False positive rate < 1%
    test_file: tests/evaluation/test_safety.py

  - id: US-090
    title: "Jailbreak Resistance"
    priority: BLOCKER
    type: testable
    acceptance_criteria:
      - System blocks prompt injection attempts
      - System maintains safe behavior under adversarial input
    test_file: tests/evaluation/test_safety.py

  - id: US-091
    title: "Safety Rules Documentation"
    priority: CRITICAL
    type: non-testable
    acceptance_criteria:
      - All safety rules documented with rationale
      - Rules reviewed by medical team
    trace_location: src/safety/rules.py
```

---

## Pattern 4: Inline Documentation References

**Use for:** Architecture docs, READMEs, design documents.

```markdown
<!-- docs/architecture.md -->

## Safety Architecture

The safety system implements multiple layers of protection:

### Emergency Detection (US-089)

Per **US-089**, the system must detect emergency symptoms and provide
immediate referral guidance. Implementation in `src/safety/detector.py`.

### Jailbreak Resistance (US-090)

Per **US-090**, all inputs are screened for prompt injection patterns.
See `src/safety/guardrails.py` for implementation.

### Related Stories

| Story | Description | Implementation |
|-------|-------------|----------------|
| US-089 | Emergency detection | `src/safety/detector.py` |
| US-090 | Jailbreak resistance | `src/safety/guardrails.py` |
| US-091 | Safety documentation | This document |
```

---

## Pattern 5: Test Coverage Matrix

**Use for:** Tracking which stories have test coverage.

```python
# tests/conftest.py
import pytest

# Story coverage tracking
STORY_COVERAGE = {
    "US-089": "tests/evaluation/test_safety.py::TestEmergencyDetection",
    "US-090": "tests/evaluation/test_safety.py::TestJailbreakResistance",
    "US-091": None,  # Non-testable, uses @trace
}


def pytest_collection_modifyitems(items):
    """Add story markers to tests for coverage tracking."""
    for item in items:
        # Extract story ID from allure markers
        for marker in item.iter_markers("allure_label"):
            if marker.args[0] == "story":
                story_id = marker.args[1].split()[0]  # "US-089 Description" -> "US-089"
                item.add_marker(pytest.mark.story(story_id))
```

---

## Pattern 6: CI Traceability Check

**Use for:** Enforcing traceability in CI/CD.

```yaml
# .github/workflows/traceability.yml
name: Traceability Check

on:
  pull_request:
    paths:
      - 'requirements/**'
      - 'backlog/**'
      - 'src/**'
      - 'tests/**'

jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: astral-sh/setup-uv@v7
      - run: uv sync --all-extras

      - name: Check for duplicate IDs
        run: uv run rdm story check-ids

      - name: Run traceability audit
        run: uv run rdm story audit

      - name: Validate backlog
        run: uv run rdm story backlog-validate backlog/
```

---

## Anti-Pattern: Comment-Only Tracing

**Don't do this:**

```python
# hh-app-003.02: Validation logic
def validate(data):
    # Implements hh-app-003.02 acceptance criteria 1
    if not data:
        return False
    # hh-app-003.02 #2: Check format
    return check_format(data)
```

**Problems:**
- Comments not programmatically traceable
- Easy to become stale
- No tooling support

**Do this instead:**

```python
@trace("hh-app-003.02", "Data validation")
@allure.story("hh-app-003.02 Data Validation")  # If testable
def validate(data):
    """Validate input data per hh-app-003.02 requirements."""
    if not data:
        return False
    return check_format(data)
```

---

## Quick Reference

| Requirement Type | Pattern | Location |
|------------------|---------|----------|
| Testable behavior | `@allure.story("{task_prefix}-NNN.NN")` | Test file |
| Process/workflow | `@trace("{task_prefix}-NNN.NN")` | Source file |
| Configuration | `@trace` on class | Config module |
| Documentation | Inline `**{task_prefix}-NNN.NN**` | Markdown |
| API contract | `@trace` + OpenAPI | Route handlers |
| Risk controls | `refs: {task_prefix}-NNN.NN:#N` | Risk docs |
