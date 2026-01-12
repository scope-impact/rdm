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

from enum import Enum
from typing import Any

try:
    from pydantic import BaseModel, Field, field_validator
except ImportError:
    raise ImportError(
        "pydantic is required for story_audit. "
        "Install with: pip install rdm[story]"
    )


# =============================================================================
# SCHEMA VERSION
# =============================================================================

SCHEMA_VERSION = "1.0.0"


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
    """A control that mitigates a risk."""

    id: str = Field(..., pattern=r"^RC-\d+$", description="Risk Control ID (RC-XXX)")
    description: str = Field(..., description="Control description")
    implemented_by: list[str] = Field(
        default_factory=list,
        description="User stories implementing this control (US-XXX)",
    )
    verification: str | None = Field(default=None, description="How control is verified")
    status: str = Field(default="proposed", description="Implementation status")

    model_config = {"extra": "allow"}


class Risk(BaseModel):
    """A risk with controls."""

    id: str = Field(..., pattern=r"^RSK-\d+$", description="Risk ID (RSK-XXX)")
    title: str = Field(..., description="Risk title")
    description: str = Field(default="", description="Risk description")
    category: str | None = Field(default=None, description="STRIDE category or custom")
    severity: str = Field(default="medium", description="Severity level")
    probability: str = Field(default="medium", description="Probability level")
    risk_level: str | None = Field(default=None, description="Calculated risk level")
    controls: list[RiskControl] = Field(
        default_factory=list, description="Controls mitigating this risk"
    )
    residual_risk: str | None = Field(default=None, description="Risk after controls")
    status: str = Field(default="identified", description="Risk status")

    model_config = {"extra": "allow"}

    def get_all_implemented_by(self) -> list[str]:
        """Get all US IDs implementing controls for this risk."""
        us_ids = []
        for control in self.controls:
            us_ids.extend(control.implemented_by)
        return us_ids


class RiskRegister(BaseModel):
    """A risk register file containing multiple risks."""

    id: str | None = Field(default=None, description="Register ID")
    title: str = Field(default="Risk Register", description="Register title")
    risks: list[Risk] = Field(default_factory=list, description="List of risks")

    model_config = {"extra": "allow"}


# =============================================================================
# USER STORY MODELS
# =============================================================================


class UserStory(BaseModel):
    """A user story within a feature."""

    id: str = Field(
        ...,
        pattern=r"^US-([A-Z]+-)?(\d+)$",
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

    id: str = Field(..., pattern=r"^FT-\d+$", description="Feature ID (FT-XXX)")
    title: str = Field(..., description="Feature title")
    epic_id: str | None = Field(
        default=None, pattern=r"^EP-\d+$", description="Parent epic ID"
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

    id: str = Field(..., pattern=r"^EP-\d+$", description="Epic ID (EP-XXX)")
    title: str
    status: str = "unknown"
    phases: list[str] = Field(default_factory=list)
    features: list[str] = Field(default_factory=list)
    note: str | None = None

    model_config = {"extra": "allow"}


class FeatureRef(BaseModel):
    """Feature reference in index file (minimal info)."""

    id: str = Field(..., pattern=r"^FT-\d+$")
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
