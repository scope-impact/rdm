"""
Pydantic schema models for requirements YAML files.

This is the SINGLE SOURCE OF TRUTH for the requirements schema.
Used by:
- audit.py (traceability analysis)
- validate.py (YAML validation)
- sync.py (DuckDB sync)

When adding new fields to YAML:
1. Add the field to the appropriate model below
2. Run `rdm story validate` to check existing files
3. Run `rdm story sync` to update the database
"""

from __future__ import annotations

import re
from enum import Enum
from typing import Any

try:
    from pydantic import BaseModel, Field, field_validator
except ImportError:
    raise ImportError(
        "pydantic is required for story_audit. "
        "Install with: pip install rdm[story-audit]"
    )


# =============================================================================
# SCHEMA VERSION
# =============================================================================

SCHEMA_VERSION = "1.1.0"  # Added extended risk schema support


# =============================================================================
# ID PATTERNS - SINGLE SOURCE OF TRUTH
# =============================================================================
# All ID validation across the module should use these constants.
# Format: PREFIX-DIGITS where DIGITS is one or more digits (not fixed to 3).

# Valid ID prefixes and their descriptions
ID_PREFIXES = {
    "FT": "Feature",
    "US": "User Story",
    "EP": "Epic",
    "RISK": "Risk",  # Format: RISK-CLUSTER-NNN (e.g., RISK-IAM-001)
    "RC": "Risk Cluster",  # Format: RC-XXX (e.g., RC-IAM)
    "DC": "Design Control",
    "GR": "Guidance Reference",
    "ADR": "Architecture Decision Record",
}

# All valid prefixes as a regex alternation
_ALL_PREFIXES = "|".join(sorted(ID_PREFIXES.keys(), key=len, reverse=True))

# Core pattern components
ID_DIGITS_PATTERN = r"\d+"  # One or more digits (flexible)

# Pattern for matching any story/requirement ID in text (word boundary)
# Matches: FT-001, US-123, EP-1, RSK-001, RC-42, DC-001, GR-001, ADR-001
# Also matches extended format: RISK-IAM-001, RISK-DATA-002
ID_PATTERN = re.compile(rf"\b({_ALL_PREFIXES})-(?:[A-Z]+-)?({ID_DIGITS_PATTERN})\b")

# Pattern for matching ID definitions in YAML (id: XX-NNN)
# Matches lines like "id: FT-001" or "- id: US-123"
ID_DEFINITION_PATTERN = re.compile(
    rf"\bid:\s*((?:{_ALL_PREFIXES})-{ID_DIGITS_PATTERN})\b"
)

# Individual prefix patterns for Pydantic field validation
# These are strings (not compiled) for use in Field(pattern=...)
FEATURE_ID_PATTERN = r"^FT-\d+$"
USER_STORY_ID_PATTERN = r"^US-([A-Z]+-)?(\d+)$"  # Allows US-001 or US-PREFIX-001
EPIC_ID_PATTERN = r"^EP-\d+$"
# Risk ID: RISK-CLUSTER-NNN (e.g., RISK-IAM-001, RISK-DATA-002)
RISK_ID_PATTERN = r"^RISK-[A-Z]+-\d+$"
# Risk Cluster ID: RC-XXX (e.g., RC-IAM, RC-DATA)
RISK_CLUSTER_ID_PATTERN = r"^RC-[A-Z]+$"


def is_valid_id(story_id: str) -> bool:
    """Check if a string is a valid story/requirement ID."""
    return ID_PATTERN.fullmatch(story_id) is not None


def get_id_prefix(story_id: str) -> str | None:
    """Extract the prefix from a story ID (e.g., 'FT' from 'FT-001')."""
    match = ID_PATTERN.fullmatch(story_id)
    return match.group(1) if match else None


def get_id_type(story_id: str) -> str | None:
    """Get the human-readable type of an ID (e.g., 'Feature' for 'FT-001')."""
    prefix = get_id_prefix(story_id)
    return ID_PREFIXES.get(prefix) if prefix else None


# =============================================================================
# ENUMS
# =============================================================================


class StoryQuality(str, Enum):
    """Quality classification for user stories."""

    CORE = "core"
    ACCEPTABLE = "acceptable"
    WEAK = "weak"
    UNKNOWN = "unknown"


class Priority(str, Enum):
    """Priority levels for features and stories."""

    CRITICAL = "critical"
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class Status(str, Enum):
    """Implementation status."""

    PROPOSED = "proposed"
    IN_PROGRESS = "in_progress"
    PARTIAL = "partial"
    IMPLEMENTED = "implemented"
    COMPLETE = "complete"
    UNKNOWN = "unknown"


# =============================================================================
# RISK MODELS
# =============================================================================


class RiskControl(BaseModel):
    """A control that mitigates a risk.

    Format: Inline control with ac_refs linking to acceptance criteria.
    Example:
        - control: "OIDC restricted to repo:scope-impact/halla-health-infra"
          ac_refs: [US-MGMT-003:AC-002]
    """

    control: str = Field(..., description="Control description")
    ac_refs: list[str] = Field(
        default_factory=list,
        description="Acceptance criteria references (US-XXX:AC-XXX)",
    )

    model_config = {"extra": "allow"}

    @property
    def story_refs(self) -> list[str]:
        """Extract user story IDs from ac_refs (US-XXX:AC-XXX -> US-XXX)."""
        refs = []
        for ac_ref in self.ac_refs:
            if ":" in ac_ref:
                us_id = ac_ref.split(":")[0]
                if us_id not in refs:
                    refs.append(us_id)
        return refs


class RiskAcceptance(BaseModel):
    """Documentation for accepted risks."""

    rationale: str = Field(..., description="Why this risk is acceptable")
    owner: str = Field(..., description="Team or role responsible")
    review_date: str | None = Field(default=None, description="Next review date (YYYY-QN)")

    model_config = {"extra": "allow"}


class RiskMitigation(BaseModel):
    """Mitigation details for a risk."""

    status: str = Field(..., description="mitigated|partial|accepted")
    controls: list[RiskControl] = Field(default_factory=list, description="Control measures")
    residual_risk: str = Field(..., description="Risk level after controls: low|medium|high")
    risk_acceptance: RiskAcceptance | None = Field(
        default=None, description="Acceptance details (only for accepted risks)"
    )

    model_config = {"extra": "allow"}


class Risk(BaseModel):
    """A risk with STRIDE chain and mitigation controls.

    Format: RISK-CLUSTER-NNN (e.g., RISK-IAM-001)

    Example:
        - id: RISK-IAM-001
          title: "OIDC Provider Trust Boundary Bypass"
          stride: spoofing
          hazard: "GitHub OIDC provider trusts GitHub-issued tokens"
          situation: "Misconfigured OIDC conditions allow unauthorized repo/branch"
          harm: "Attacker gains AWS access; infrastructure takeover; data breach"
          severity: critical
          probability: unlikely
          level: high
          mitigation:
            status: mitigated
            controls: [...]
            residual_risk: low
    """

    id: str = Field(..., pattern=RISK_ID_PATTERN, description="Risk ID (RISK-CLUSTER-NNN)")
    title: str = Field(..., description="Risk title")

    # STRIDE chain (required for proper risk documentation)
    stride: str = Field(
        ...,
        description="STRIDE category: spoofing|tampering|repudiation|info_disclosure|dos|elevation",
    )
    hazard: str = Field(..., description="What could go wrong - the threat source")
    situation: str = Field(..., description="How it manifests - the attack scenario")
    harm: str = Field(..., description="Who gets hurt and how - the impact")

    # Severity × Probability scoring
    severity: str = Field(..., description="Severity: critical|serious|minor|negligible")
    probability: str = Field(..., description="Probability: rare|unlikely|possible|likely")
    level: str = Field(..., description="Risk level from matrix: low|medium|high|block")

    # Optional details
    description: str = Field(default="", description="Additional context about the risk")
    affected_requirements: list[str] = Field(
        default_factory=list, description="User story IDs affected by this risk"
    )

    # Mitigation (required)
    mitigation: RiskMitigation = Field(..., description="Mitigation details")

    model_config = {"extra": "allow"}

    @property
    def controls(self) -> list[RiskControl]:
        """Get controls from mitigation."""
        return self.mitigation.controls

    @property
    def residual_risk(self) -> str:
        """Get residual risk from mitigation."""
        return self.mitigation.residual_risk

    @property
    def status(self) -> str:
        """Get status from mitigation."""
        return self.mitigation.status

    def get_all_story_refs(self) -> list[str]:
        """Get all user story IDs from controls."""
        us_ids = []
        for control in self.controls:
            us_ids.extend(control.story_refs)
        return list(set(us_ids))

    def get_all_affected_requirements(self) -> list[str]:
        """Get all affected requirement IDs including from controls."""
        reqs = list(self.affected_requirements)
        reqs.extend(self.get_all_story_refs())
        return list(set(reqs))


class RiskClusterMetadata(BaseModel):
    """Metadata for a risk cluster."""

    cluster_id: str = Field(..., pattern=RISK_CLUSTER_ID_PATTERN, description="Cluster ID (RC-XXX)")
    cluster_name: str = Field(..., description="Cluster name")
    description: str = Field(default="", description="What this risk cluster covers")
    stride_categories: list[str] = Field(default_factory=list, description="STRIDE categories")
    assessed_date: str | None = Field(default=None, description="Assessment date (YYYY-MM-DD)")
    root_risk: str | None = Field(default=None, description="Primary threat this cluster addresses")

    model_config = {"extra": "allow"}


class RiskCluster(BaseModel):
    """A risk cluster file containing related risks.

    Format: One file per cluster (e.g., iam.yaml, data.yaml, network.yaml)

    Example file structure:
        metadata:
          cluster_id: RC-IAM
          cluster_name: "Identity & Access Management"
          description: "Risks related to authentication and authorization"
          stride_categories: [spoofing, elevation]
          assessed_date: "2025-01-14"
          root_risk: "Unauthorized access to AWS resources"

        affected_requirements:
          - US-MGMT-002
          - US-MGMT-003

        risks:
          - id: RISK-IAM-001
            title: "OIDC Provider Trust Boundary Bypass"
            ...
    """

    # Cluster metadata
    metadata: RiskClusterMetadata = Field(..., description="Cluster metadata")
    affected_requirements: list[str] = Field(
        default_factory=list, description="User story IDs affected by this cluster"
    )
    risks: list[Risk] = Field(default_factory=list, description="List of risks in this cluster")

    model_config = {"extra": "allow"}

    @property
    def cluster_id(self) -> str:
        """Get cluster ID from metadata."""
        return self.metadata.cluster_id

    @property
    def cluster_name(self) -> str:
        """Get cluster name from metadata."""
        return self.metadata.cluster_name

    def get_all_affected_requirements(self) -> list[str]:
        """Get all affected requirements from cluster and all risks."""
        reqs = list(self.affected_requirements)
        for risk in self.risks:
            reqs.extend(risk.get_all_affected_requirements())
        return list(set(reqs))


# Alias for backward compatibility with sync.py
RiskRegister = RiskCluster


# =============================================================================
# USER STORY MODELS
# =============================================================================


class UserStory(BaseModel):
    """A user story within a feature."""

    id: str = Field(
        ...,
        pattern=USER_STORY_ID_PATTERN,
        description="User story ID (US-XXX or US-PREFIX-XXX)",
    )
    as_a: str = Field(default="", description="Role (As a...)")
    i_want: str = Field(default="", description="Goal (I want...)")
    so_that: str = Field(default="", description="Benefit (So that...)")
    acceptance_criteria: list[str] = Field(
        default_factory=list, description="List of acceptance criteria"
    )
    priority: str = Field(default="medium", description="Story priority")
    story_quality: str = Field(default="unknown", description="Quality classification")
    status: str | None = Field(default=None, description="Implementation status")
    note: str | None = Field(default=None, description="Additional notes")

    model_config = {"extra": "allow"}

    @property
    def full_story(self) -> str:
        """Generate full user story text."""
        return f"As a {self.as_a}, I want {self.i_want} so that {self.so_that}"

    def get_extra_fields(self) -> dict[str, Any]:
        """Return any extra fields not in the schema."""
        # Pydantic V2 stores extra fields in __pydantic_extra__
        extra = getattr(self, "__pydantic_extra__", None)
        return dict(extra) if extra else {}


# =============================================================================
# FEATURE MODELS
# =============================================================================


class StoryQualitySummary(BaseModel):
    """Summary of story quality distribution in a feature."""

    core: int = 0
    acceptable: int = 0
    weak: int = 0

    model_config = {"extra": "allow"}


class TechnicalSpec(BaseModel):
    """Technical specification for a feature."""

    implementation_notes: str | None = None
    dependencies: list[str] = Field(default_factory=list)
    api_changes: list[str] = Field(default_factory=list)

    model_config = {"extra": "allow"}


class ExistingCode(BaseModel):
    """References to existing code for a feature."""

    files: list[str] = Field(default_factory=list)
    tests: list[str] = Field(default_factory=list)

    model_config = {"extra": "allow"}


class Feature(BaseModel):
    """A feature specification."""

    id: str = Field(..., pattern=FEATURE_ID_PATTERN, description="Feature ID (FT-XXX)")
    title: str = Field(..., description="Feature title")
    epic_id: str | None = Field(
        default=None, pattern=EPIC_ID_PATTERN, description="Parent epic ID"
    )
    phase: str | None = Field(default=None, description="Implementation phase")
    priority: str = Field(default="medium", description="Feature priority")
    status: str = Field(default="unknown", description="Implementation status")
    description: str = Field(default="", description="Problem/solution description")
    business_value: str = Field(default="", description="Business value statement")
    user_stories: list[UserStory] = Field(
        default_factory=list, description="User stories in this feature"
    )
    definition_of_done: list[str] | dict[str, list[str]] = Field(
        default_factory=list,
        description="Definition of done items (list or dict with categories)",
    )
    labels: list[str] = Field(default_factory=list, description="Feature labels/tags")
    story_quality_summary: StoryQualitySummary | None = Field(
        default=None, description="Quality distribution summary"
    )
    technical_spec: TechnicalSpec | dict | None = Field(
        default=None, description="Technical specification"
    )
    existing_code: ExistingCode | dict | None = Field(
        default=None, description="Existing code references"
    )
    note: str | None = Field(default=None, description="Additional notes")

    model_config = {"extra": "allow"}

    @field_validator("story_quality_summary", mode="before")
    @classmethod
    def parse_quality_summary(cls, v: Any) -> StoryQualitySummary | None:
        if v is None:
            return None
        if isinstance(v, dict):
            return StoryQualitySummary(**v)
        return v

    def get_extra_fields(self) -> dict[str, Any]:
        """Return any extra fields not in the schema."""
        # Pydantic V2 stores extra fields in __pydantic_extra__
        extra = getattr(self, "__pydantic_extra__", None)
        return dict(extra) if extra else {}

    def compute_quality_summary(self) -> StoryQualitySummary:
        """Compute story quality summary from user stories."""
        summary = StoryQualitySummary()
        for story in self.user_stories:
            quality = story.story_quality.lower()
            if quality == "core":
                summary.core += 1
            elif quality == "acceptable":
                summary.acceptable += 1
            elif quality == "weak":
                summary.weak += 1
        return summary


# =============================================================================
# INDEX FILE MODELS
# =============================================================================


class Project(BaseModel):
    """Project metadata."""

    name: str
    description: str = ""
    scope: str = ""

    model_config = {"extra": "allow"}


class Phase(BaseModel):
    """A development phase."""

    description: str = ""
    features: list[str] = Field(default_factory=list)

    model_config = {"extra": "allow"}


class Epic(BaseModel):
    """An epic grouping features."""

    id: str = Field(..., pattern=EPIC_ID_PATTERN, description="Epic ID (EP-XXX)")
    title: str
    status: str = "unknown"
    phases: list[str] = Field(default_factory=list)
    features: list[str] = Field(default_factory=list)
    note: str | None = None

    model_config = {"extra": "allow"}


class FeatureRef(BaseModel):
    """Feature reference in index file (minimal info)."""

    id: str = Field(..., pattern=FEATURE_ID_PATTERN)
    title: str
    phase: str | None = None
    epic: str | None = None
    status: str = "unknown"
    note: str | None = None

    model_config = {"extra": "allow"}


class RequirementsIndex(BaseModel):
    """The _index.yaml file structure."""

    project: Project | None = None
    phases: dict[str, Phase] = Field(default_factory=dict)
    epics: list[Epic] = Field(default_factory=list)
    features: list[FeatureRef] = Field(default_factory=list)

    model_config = {"extra": "allow"}

    @field_validator("phases", mode="before")
    @classmethod
    def parse_phases(cls, v: Any) -> dict[str, Phase]:
        if v is None:
            return {}
        if isinstance(v, dict):
            return {k: Phase(**p) if isinstance(p, dict) else p for k, p in v.items()}
        return v


# =============================================================================
# HELPER FUNCTIONS
# =============================================================================


def get_all_field_names(model: type[BaseModel]) -> set[str]:
    """Get all field names from a Pydantic model."""
    return set(model.model_fields.keys())


def model_to_flat_dict(instance: BaseModel, prefix: str = "") -> dict[str, Any]:
    """Flatten a Pydantic model to a dict suitable for database insertion."""
    result = {}
    for field_name, field_value in instance:
        key = f"{prefix}{field_name}" if prefix else field_name

        if isinstance(field_value, BaseModel):
            result.update(model_to_flat_dict(field_value, f"{key}_"))
        elif isinstance(field_value, list):
            result[key] = field_value
            result[f"{key}_count"] = len(field_value)
        else:
            result[key] = field_value

    return result
