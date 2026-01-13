"""
Generate schema documentation for requirements YAML files.

Usage:
    rdm story schema [--model Feature|Epic|UserStory|Risk|All]
"""

from __future__ import annotations

import sys
from typing import Any

try:
    import yaml
except ImportError as e:
    raise ImportError(
        f"Missing dependency: {e}. Install with: pip install rdm[story]"
    )

from rdm.story_audit.schema import (
    SCHEMA_VERSION,
    ID_PREFIXES,
)


# =============================================================================
# YAML EXAMPLE TEMPLATES
# =============================================================================

FEATURE_EXAMPLE = {
    "_comment": f"Feature (FT-XXX) - Schema Version: {SCHEMA_VERSION}",
    "id": "FT-001",
    "title": "User Authentication",
    "epic_id": "EP-001",
    "phase": "phase_1",
    "priority": "high",
    "status": "proposed",
    "description": "Secure user authentication system",
    "business_value": "Protects user data and ensures compliance",
    "labels": ["security", "core"],
    "user_stories": [
        {
            "id": "US-001",
            "as_a": "user",
            "i_want": "log in securely",
            "so_that": "my data is protected",
            "acceptance_criteria": [
                "AC-001: Password must be hashed",
                "AC-002: Session expires after 30 minutes",
            ],
            "priority": "high",
            "story_quality": "core",
            "status": "proposed",
        },
        {
            "id": "US-002",
            "as_a": "administrator",
            "i_want": "manage user accounts",
            "so_that": "I can control access",
            "acceptance_criteria": [
                "AC-001: Can create new users",
                "AC-002: Can disable accounts",
            ],
            "priority": "medium",
            "story_quality": "acceptable",
        },
    ],
    "definition_of_done": [
        "Code reviewed and approved",
        "Unit tests pass (>80% coverage)",
        "Security review completed",
    ],
    "technical_spec": {
        "implementation_notes": "Use bcrypt for password hashing",
        "dependencies": ["bcrypt", "jwt"],
        "api_changes": ["/api/auth/login", "/api/auth/logout"],
    },
}

EPIC_EXAMPLE = {
    "_comment": f"Epic (EP-XXX) - Schema Version: {SCHEMA_VERSION}",
    "id": "EP-001",
    "title": "Core Platform",
    "status": "in_progress",
    "phases": ["phase_1", "phase_2"],
    "features": ["FT-001", "FT-002", "FT-003"],
    "note": "Foundation features for the platform",
}

USER_STORY_EXAMPLE = {
    "_comment": f"UserStory (US-XXX) - Schema Version: {SCHEMA_VERSION}",
    "id": "US-001",
    "as_a": "healthcare provider",
    "i_want": "log in securely to the system",
    "so_that": "patient data is protected",
    "acceptance_criteria": [
        "AC-001: Password must be hashed with bcrypt",
        "AC-002: Account locks after 5 failed attempts",
        "AC-003: Session expires after inactivity",
    ],
    "priority": "high",
    "story_quality": "core",
    "status": "proposed",
    "note": "Critical security requirement",
}

RISK_EXAMPLE = {
    "_comment": f"Risk (RSK-XXX) - Schema Version: {SCHEMA_VERSION}",
    "id": "RSK-001",
    "title": "Unauthorized access to patient data",
    "description": "Attackers may gain unauthorized access to sensitive patient information",
    "category": "Spoofing",
    "severity": "high",
    "probability": "medium",
    "risk_level": "high",
    "status": "identified",
    "controls": [
        {
            "id": "RC-001",
            "description": "Implement multi-factor authentication",
            "implemented_by": ["US-001", "US-002"],
            "verification": "Security audit checklist",
            "status": "proposed",
        },
        {
            "id": "RC-002",
            "description": "Encrypt data at rest and in transit",
            "implemented_by": ["US-003"],
            "verification": "Penetration testing",
            "status": "proposed",
        },
    ],
    "residual_risk": "low",
}

INDEX_EXAMPLE = {
    "_comment": f"RequirementsIndex (_index.yaml) - Schema Version: {SCHEMA_VERSION}",
    "project": {
        "name": "Medical Device Software",
        "description": "Patient monitoring system",
        "scope": "Core platform and integrations",
    },
    "phases": {
        "phase_1": {
            "description": "Foundation and security",
            "features": ["FT-001", "FT-002"],
        },
        "phase_2": {
            "description": "Advanced features",
            "features": ["FT-003", "FT-004"],
        },
    },
}


# =============================================================================
# SCHEMA FIELD DOCUMENTATION
# =============================================================================

FEATURE_FIELDS = """
# Feature Schema Fields
# =====================
# File location: features/FT-XXX.yaml

# Required fields:
#   id:           string, pattern: ^FT-\\d+$
#   title:        string

# Optional fields:
#   epic_id:      string, pattern: ^EP-\\d+$ (reference to parent epic)
#   phase:        string (phase identifier)
#   priority:     string, enum: critical|high|medium|low, default: medium
#   status:       string, enum: proposed|in_progress|partial|implemented|complete|unknown
#   description:  string (problem/solution description)
#   business_value: string
#   labels:       list of strings
#   note:         string
#   user_stories: list of UserStory objects
#   definition_of_done: list of strings OR dict with categories
#   technical_spec: object with implementation_notes, dependencies, api_changes
#   existing_code: object with files, tests lists
#   story_quality_summary: object with core, acceptable, weak counts (auto-computed)
"""

EPIC_FIELDS = """
# Epic Schema Fields
# ==================
# File location: epics/EP-XXX.yaml

# Required fields:
#   id:           string, pattern: ^EP-\\d+$
#   title:        string

# Optional fields:
#   status:       string, default: unknown
#   phases:       list of phase IDs
#   features:     list of feature IDs (FT-XXX references, NOT full objects)
#   note:         string
"""

USER_STORY_FIELDS = """
# UserStory Schema Fields
# =======================
# File location: nested within features/FT-XXX.yaml under user_stories

# Required fields:
#   id:           string, pattern: ^US-([A-Z]+-)?\\d+$ (e.g., US-001 or US-AUTH-001)

# Optional fields:
#   as_a:         string (role description)
#   i_want:       string (goal statement)
#   so_that:      string (benefit statement)
#   acceptance_criteria: list of strings (format: "AC-XXX: description")
#   priority:     string, enum: critical|high|medium|low, default: medium
#   story_quality: string, enum: core|acceptable|weak|unknown, default: unknown
#   status:       string
#   note:         string
"""

RISK_FIELDS = """
# Risk Schema Fields
# ==================
# File location: risks/RSK-XXX.yaml

# Required fields:
#   id:           string, pattern: ^RSK-\\d+$
#   title:        string

# Optional fields:
#   description:  string
#   category:     string (STRIDE category or custom)
#   severity:     string, default: medium
#   probability:  string, default: medium
#   risk_level:   string (calculated)
#   residual_risk: string (after controls)
#   status:       string, default: identified
#   controls:     list of RiskControl objects

# RiskControl fields:
#   id:           string, pattern: ^RC-\\d+$ (required)
#   description:  string (required)
#   implemented_by: list of story IDs (US-XXX)
#   verification: string
#   status:       string, default: proposed
"""

INDEX_FIELDS = """
# RequirementsIndex Schema Fields
# ===============================
# File location: _index.yaml

# All fields are optional:
#   project:      object with name (required), description, scope
#   phases:       dict mapping phase_id to Phase object
#   epics:        list of Epic objects (prefer using epics/*.yaml instead)
#   features:     list of FeatureRef objects (prefer using features/*.yaml instead)

# NOTE: _index.yaml should primarily contain project metadata and phase groupings.
# Define epics in epics/*.yaml and features in features/*.yaml to avoid duplicates.
"""


# =============================================================================
# PRINT FUNCTIONS
# =============================================================================


def _format_yaml(data: dict[str, Any]) -> str:
    """Format a dictionary as YAML, removing _comment field for valid output."""
    # Extract comment for header
    comment = data.pop("_comment", None)
    output = ""
    if comment:
        output = f"# {comment}\n\n"
    output += yaml.dump(data, default_flow_style=False, sort_keys=False, allow_unicode=True)
    # Restore comment for next use
    if comment:
        data["_comment"] = comment
    return output


def print_feature_schema() -> None:
    """Print the Feature schema."""
    print("# Feature (FT-XXX) - defined in features/*.yaml")
    print(f"# Schema Version: {SCHEMA_VERSION}")
    print(FEATURE_FIELDS)
    print("\n# Example:")
    print(_format_yaml(FEATURE_EXAMPLE.copy()))


def print_epic_schema() -> None:
    """Print the Epic schema."""
    print("# Epic (EP-XXX) - defined in epics/*.yaml")
    print(f"# Schema Version: {SCHEMA_VERSION}")
    print(EPIC_FIELDS)
    print("\n# Example:")
    print(_format_yaml(EPIC_EXAMPLE.copy()))


def print_user_story_schema() -> None:
    """Print the UserStory schema."""
    print("# UserStory (US-XXX) - defined within features/*.yaml")
    print(f"# Schema Version: {SCHEMA_VERSION}")
    print(USER_STORY_FIELDS)
    print("\n# Example (as nested item in user_stories list):")
    print("user_stories:")
    # Indent the example for proper YAML nesting
    example_yaml = yaml.dump([USER_STORY_EXAMPLE.copy()], default_flow_style=False, sort_keys=False)
    for line in example_yaml.split("\n"):
        if line.strip():
            print(f"  {line}")


def print_risk_schema() -> None:
    """Print the Risk schema."""
    print("# Risk (RSK-XXX) - defined in risks/*.yaml")
    print(f"# Schema Version: {SCHEMA_VERSION}")
    print(RISK_FIELDS)
    print("\n# Example:")
    print(_format_yaml(RISK_EXAMPLE.copy()))


def print_index_schema() -> None:
    """Print the RequirementsIndex schema."""
    print("# RequirementsIndex - defined in _index.yaml")
    print(f"# Schema Version: {SCHEMA_VERSION}")
    print(INDEX_FIELDS)
    print("\n# Example:")
    print(_format_yaml(INDEX_EXAMPLE.copy()))


def print_architecture_docs() -> None:
    """Print requirements architecture documentation."""
    print(f"""
================================================================================
REQUIREMENTS STRUCTURE - Architecture Documentation
================================================================================
Schema Version: {SCHEMA_VERSION}

OVERVIEW
--------
Requirements are organized in a directory structure that separates concerns
and avoids duplication. Each type of requirement has its own directory.

DIRECTORY STRUCTURE
-------------------

requirements/
├── _index.yaml          # Project metadata, phases (NO full definitions)
│   ├── project: {{}}      # Name, description, scope
│   ├── phases: {{}}        # Phase groupings with feature lists
│   └── # epics/features as comments or minimal refs only
│
├── epics/               # Epic DEFINITIONS (source of truth)
│   ├── EP-001-core.yaml
│   ├── EP-002-auth.yaml
│   └── ...
│
├── features/            # Feature DEFINITIONS (source of truth)
│   ├── FT-001-bootstrap.yaml
│   ├── FT-002-auth.yaml
│   └── ...
│
└── risks/               # Risk DEFINITIONS
    ├── RSK-001-security.yaml
    └── ...


FILE RESPONSIBILITIES
---------------------

_index.yaml (Project Metadata)
  - Project name, description, scope
  - Phase definitions with feature ID lists (references only)
  - Should NOT contain full epic or feature definitions
  - Use comments to document structure without triggering ID conflicts

epics/EP-XXX.yaml (Epic Definitions)
  - id: EP-XXX           # Required, unique
  - title: str           # Required
  - features: [FT-XXX]   # List of feature IDs (references only)
  - status, phases, note # Optional fields

features/FT-XXX.yaml (Feature Definitions)
  - id: FT-XXX           # Required, unique
  - title: str           # Required
  - epic_id: EP-XXX      # Links back to epic (reference)
  - user_stories: []     # Full UserStory definitions here
  - All other feature fields...

risks/RSK-XXX.yaml (Risk Definitions)
  - id: RSK-XXX          # Required, unique
  - title: str           # Required
  - controls: []         # Full RiskControl definitions here


KEY PRINCIPLES
--------------

1. SINGLE SOURCE OF TRUTH
   - Each ID should be DEFINED in exactly one place
   - Features: features/FT-XXX.yaml
   - Epics: epics/EP-XXX.yaml
   - Risks: risks/RSK-XXX.yaml

2. REFERENCES vs DEFINITIONS
   - Use 'id:' only for definitions
   - Use ID lists for references: features: [FT-001, FT-002]
   - epic_id: EP-001 is a reference, not a definition

3. AVOID DUPLICATION
   - Don't put full definitions in _index.yaml
   - Use phases to organize, not redefine
   - If you need to reference a feature, use its ID only


COMMON MISTAKES
---------------

1. Duplicating epic definitions:
   WRONG:  _index.yaml has epics: [{{id: EP-001, title: ...}}]
           AND epics/EP-001.yaml exists
   RIGHT:  Define in epics/EP-001.yaml only

2. Using 'ref:' instead of 'id:':
   WRONG:  ref: FT-001
   RIGHT:  id: FT-001

3. Full objects in reference lists:
   WRONG:  features: [{{ref: FT-001, title: "..."}}]
   RIGHT:  features: [FT-001, FT-002, FT-003]


VALIDATION COMMANDS
-------------------

  rdm story validate             # Validate all files
  rdm story validate -v          # Verbose mode (show warnings)
  rdm story validate --suggest-fixes  # Get fix suggestions
  rdm story check-ids            # Check for duplicate IDs
  rdm story check-ids --explain  # Detailed conflict analysis
  rdm story schema               # Show field documentation
  rdm story init                 # Create new structure
""")


def print_all_schemas() -> None:
    """Print all schemas."""
    print("=" * 70)
    print("RDM STORY AUDIT - YAML SCHEMA DOCUMENTATION")
    print(f"Schema Version: {SCHEMA_VERSION}")
    print("=" * 70)
    print()

    print("-" * 70)
    print_feature_schema()
    print()

    print("-" * 70)
    print_epic_schema()
    print()

    print("-" * 70)
    print_user_story_schema()
    print()

    print("-" * 70)
    print_risk_schema()
    print()

    print("-" * 70)
    print_index_schema()
    print()

    print("=" * 70)
    print("ID PREFIX REFERENCE")
    print("=" * 70)
    for prefix, description in ID_PREFIXES.items():
        print(f"  {prefix}-XXX  {description}")
    print()


# =============================================================================
# CLI ENTRY POINT
# =============================================================================


def story_schema_command(model: str | None = None) -> int:
    """Run story schema command.

    Args:
        model: Model name to show schema for (Feature, Epic, UserStory, Risk, All, Docs)

    Returns:
        0 on success
    """
    model_lower = (model or "all").lower()

    if model_lower == "all":
        print_all_schemas()
    elif model_lower == "feature":
        print_feature_schema()
    elif model_lower == "epic":
        print_epic_schema()
    elif model_lower == "userstory" or model_lower == "user_story":
        print_user_story_schema()
    elif model_lower == "risk":
        print_risk_schema()
    elif model_lower == "index" or model_lower == "requirementsindex":
        print_index_schema()
    elif model_lower == "docs" or model_lower == "architecture":
        print_architecture_docs()
    else:
        print(f"Unknown model: {model}")
        print("Available models: Feature, Epic, UserStory, Risk, Index, Docs, All")
        return 1

    return 0


def story_docs_command() -> int:
    """Run story docs command - print architecture documentation.

    Returns:
        0 on success
    """
    print_architecture_docs()
    return 0


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(
        description="Show YAML schema documentation for requirements"
    )
    parser.add_argument(
        "--model", "-m",
        choices=["Feature", "Epic", "UserStory", "Risk", "Index", "Docs", "All"],
        default="All",
        help="Model to show schema for (default: All)",
    )
    args = parser.parse_args()

    sys.exit(story_schema_command(args.model))


if __name__ == "__main__":
    main()
