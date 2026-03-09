"""
Pydantic schema models for Backlog.md format.

This is the SINGLE SOURCE OF TRUTH for the Backlog.md schema.
Used by:
- backlog_parser.py (markdown parsing)
- sync.py (DuckDB sync)

Schema Version: 2.0.0 - Breaking change from YAML format
"""

from __future__ import annotations

from typing import Literal

try:
    from pydantic import BaseModel, Field
except ImportError:
    raise ImportError(
        "pydantic is required for story_audit. "
        "Install with: pip install rdm[story-audit]"
    )


# =============================================================================
# SCHEMA VERSION
# =============================================================================

SCHEMA_VERSION = "2.0.0"  # Breaking change from YAML format


# =============================================================================
# CONFIG MODEL
# =============================================================================


class BacklogConfig(BaseModel):
    """config.yml in backlog root.

    Example:
        project_id: "hhi"
        task_prefix: "ft"
        project_name: "Halla Health Infrastructure"
        description: "AWS multi-account infrastructure..."
        repository: "scope-impact/halla-health-infra"
        labels:
          - bootstrap
          - networking
    """

    project_id: str = Field(..., description="Unique project identifier (e.g., 'hhi')")
    task_prefix: str = Field(..., description="Prefix for task IDs (e.g., 'ft')")
    project_name: str = Field(..., description="Human-readable project name")
    description: str = Field(default="", description="Project description")
    repository: str | None = Field(default=None, description="GitHub repository")
    created_date: str | None = Field(default=None, description="Creation date")
    labels: list[str] = Field(default_factory=list, description="Default labels")

    model_config = {"extra": "allow"}

    @property
    def global_prefix(self) -> str:
        """Generate global ID prefix: {project_id}:{task_prefix}."""
        return f"{self.project_id}:{self.task_prefix}"


# =============================================================================
# MILESTONE MODEL
# =============================================================================


class Milestone(BaseModel):
    """milestones/*.md frontmatter.

    Example:
        id: m-1
        title: "Platform Foundation"
        status: in_progress
        labels: [EP-001, foundation]
    """

    id: str = Field(..., description="Milestone ID (m-1, m-2)")
    title: str = Field(..., description="Milestone title")
    status: str = Field(default="active", description="Status: active, completed")
    created_date: str | None = Field(default=None, description="Creation date")
    labels: list[str] = Field(default_factory=list, description="Labels")

    # Body content (parsed from markdown)
    description: str = Field(default="", description="Description from body")
    features: list[str] = Field(default_factory=list, description="Feature IDs listed")

    model_config = {"extra": "allow"}


# =============================================================================
# TASK MODELS
# =============================================================================


class AcceptanceCriterion(BaseModel):
    """Parsed from markdown checkboxes.

    Format: `- [x] #1 text` or `- [ ] #2 text`
    """

    number: int = Field(..., description="AC number (1, 2, 3...)")
    text: str = Field(..., description="AC text")
    completed: bool = Field(default=False, description="Checkbox state")

    model_config = {"extra": "allow"}


class Task(BaseModel):
    """tasks/*.md or completed/*.md frontmatter.

    Example:
        id: ft-003
        title: "Compute Infrastructure - Kubernetes, ALB, and Database"
        status: In Progress
        labels: [FT-003, EP-001, kubernetes]
        milestone: m-1
        priority: high
    """

    id: str = Field(..., description="Task ID (ft-003 or ft-003.01)")
    title: str = Field(..., description="Task title")
    status: str = Field(..., description="Done, In Progress, To Do")
    parent_task_id: str | None = Field(default=None, description="Parent task ID for subtasks")
    labels: list[str] = Field(default_factory=list, description="Labels")
    milestone: str | None = Field(default=None, description="Milestone ID (m-1)")
    priority: str = Field(default="medium", description="Priority: high, medium, low")
    created_date: str | None = Field(default=None, description="Creation date")

    # Body content (parsed from markdown)
    description: str = Field(default="", description="Description from body")
    business_value: str = Field(default="", description="Business value from body")
    acceptance_criteria: list[AcceptanceCriterion] = Field(
        default_factory=list, description="Parsed AC items"
    )
    subtask_ids: list[str] = Field(default_factory=list, description="Subtask IDs listed")

    # Source tracking
    source_file: str | None = Field(default=None, description="Source filename")

    model_config = {"extra": "allow"}

    @property
    def is_subtask(self) -> bool:
        """Check if this is a subtask (has parent_task_id)."""
        return self.parent_task_id is not None

    @property
    def acceptance_criteria_count(self) -> int:
        """Count of acceptance criteria."""
        return len(self.acceptance_criteria)

    @property
    def completed_criteria_count(self) -> int:
        """Count of completed acceptance criteria."""
        return sum(1 for ac in self.acceptance_criteria if ac.completed)


# =============================================================================
# RISK MODELS
# =============================================================================


class RiskDoc(BaseModel):
    """docs/doc-risk-*.md frontmatter.

    Example:
        id: doc-risk-iam-001
        title: "RISK-IAM-001: OIDC Provider Trust Boundary Bypass"
        type: risk
        labels: [risk, RC-IAM, spoofing, high-severity]
    """

    id: str = Field(..., description="Risk doc ID (doc-risk-iam-001)")
    title: str = Field(..., description="Risk title")
    type: Literal["risk"] = Field(default="risk", description="Document type")
    created_date: str | None = Field(default=None, description="Creation date")
    labels: list[str] = Field(default_factory=list, description="Labels")

    # Body content (parsed from risk table)
    stride_category: str | None = Field(default=None, description="STRIDE category")
    severity: str | None = Field(default=None, description="Severity level")
    probability: str | None = Field(default=None, description="Probability level")
    risk_level: str | None = Field(default=None, description="Risk level")
    cluster: str | None = Field(default=None, description="Risk cluster (RC-IAM)")

    # Hazard-Situation-Harm chain
    hazard: str = Field(default="", description="What could go wrong")
    situation: str = Field(default="", description="How it manifests")
    harm: str = Field(default="", description="Impact/who gets hurt")
    description: str = Field(default="", description="Additional context")

    # Traceability
    affected_requirements: list[str] = Field(
        default_factory=list, description="Affected requirement IDs"
    )

    # Mitigation
    mitigation_status: str | None = Field(default=None, description="Mitigated/Accepted/Open")
    residual_risk: str | None = Field(default=None, description="Residual risk level")
    controls: list[str] = Field(default_factory=list, description="Control descriptions")
    control_refs: list[list[str]] = Field(
        default_factory=list, description="AC refs for each control"
    )

    # Source tracking
    source_file: str | None = Field(default=None, description="Source filename")

    model_config = {"extra": "allow"}


# =============================================================================
# DECISION MODEL
# =============================================================================


class Decision(BaseModel):
    """decisions/*.md frontmatter.

    Example:
        id: decision-1
        title: "ADR-001: Public Subnet for EC2 (No NAT Gateway)"
        date: '2026-01-17'
        status: accepted
        labels: [networking, ec2, security]
    """

    id: str = Field(..., description="Decision ID (decision-1)")
    title: str = Field(..., description="Decision title")
    date: str | None = Field(default=None, description="Decision date")
    status: str = Field(default="accepted", description="accepted, deprecated, superseded")
    labels: list[str] = Field(default_factory=list, description="Labels")

    # Body content (parsed from markdown)
    context: str = Field(default="", description="Context section")
    decision: str = Field(default="", description="Decision section")
    rationale: str = Field(default="", description="Rationale section")
    consequences: str = Field(default="", description="Consequences section")

    # Source tracking
    source_file: str | None = Field(default=None, description="Source filename")

    model_config = {"extra": "allow"}


# =============================================================================
# BACKLOG COLLECTION MODEL
# =============================================================================


class BacklogData(BaseModel):
    """Complete backlog data extracted from a Backlog.md directory."""

    config: BacklogConfig
    milestones: list[Milestone] = Field(default_factory=list)
    tasks: list[Task] = Field(default_factory=list)  # Parent tasks only
    subtasks: list[Task] = Field(default_factory=list)  # Subtasks with parent_task_id
    risks: list[RiskDoc] = Field(default_factory=list)
    decisions: list[Decision] = Field(default_factory=list)

    model_config = {"extra": "allow"}

    @property
    def project_id(self) -> str:
        """Get project ID from config."""
        return self.config.project_id

    def make_global_id(self, local_id: str) -> str:
        """Generate global ID: {project_id}:{local_id}."""
        return f"{self.config.project_id}:{local_id}"
