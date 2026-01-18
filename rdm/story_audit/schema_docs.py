"""
Generate schema documentation from Pydantic models.

Usage:
    rdm story schema [--model Feature|Epic|UserStory|Risk|All]
"""

from __future__ import annotations

import sys

from rdm.story_audit.schema import (
    SCHEMA_VERSION,
    ID_PREFIXES,
    Feature,
    UserStory,
    Epic,
    Risk,
    RiskControl,
    RequirementsIndex,
)


def _print_model_fields(model: type, indent: str = "") -> None:
    """Print model fields with types and descriptions."""
    for name, field_info in model.model_fields.items():
        field_type = str(field_info.annotation).replace("typing.", "")
        required = "required" if field_info.is_required() else "optional"
        desc = field_info.description or ""
        print(f"{indent}{name}: {field_type}  # {required}{', ' + desc if desc else ''}")


def print_feature_schema() -> None:
    """Print Feature schema from Pydantic model."""
    print(f"# Feature (FT-XXX) - v{SCHEMA_VERSION}")
    print("# Location: requirements/features/FT-XXX.yaml\n")
    _print_model_fields(Feature)
    print("\n# Nested: user_stories[]")
    _print_model_fields(UserStory, indent="  ")


def print_epic_schema() -> None:
    """Print Epic schema from Pydantic model."""
    print(f"# Epic (EP-XXX) - v{SCHEMA_VERSION}")
    print("# Location: requirements/_index.yaml (epics section)\n")
    _print_model_fields(Epic)


def print_user_story_schema() -> None:
    """Print UserStory schema from Pydantic model."""
    print(f"# UserStory (US-XXX) - v{SCHEMA_VERSION}")
    print("# Location: nested in Feature.user_stories[]\n")
    _print_model_fields(UserStory)


def print_risk_schema() -> None:
    """Print Risk schema from Pydantic model."""
    print(f"# Risk (RSK-XXX) - v{SCHEMA_VERSION}")
    print("# Location: requirements/risks/*.yaml\n")
    _print_model_fields(Risk)
    print("\n# Nested: controls[]")
    _print_model_fields(RiskControl, indent="  ")


def print_index_schema() -> None:
    """Print RequirementsIndex schema from Pydantic model."""
    print(f"# RequirementsIndex - v{SCHEMA_VERSION}")
    print("# Location: requirements/_index.yaml\n")
    _print_model_fields(RequirementsIndex)


def print_all_schemas() -> None:
    """Print all schemas."""
    print("=" * 60)
    print(f"RDM STORY AUDIT SCHEMA - v{SCHEMA_VERSION}")
    print("=" * 60)

    print("\n" + "-" * 60)
    print_feature_schema()

    print("\n" + "-" * 60)
    print_epic_schema()

    print("\n" + "-" * 60)
    print_risk_schema()

    print("\n" + "-" * 60)
    print_index_schema()

    print("\n" + "=" * 60)
    print("ID PREFIXES")
    print("=" * 60)
    for prefix, desc in ID_PREFIXES.items():
        print(f"  {prefix}-XXX  {desc}")


def story_schema_command(model: str | None = None) -> int:
    """Run story schema command."""
    model_lower = (model or "all").lower()

    if model_lower == "all":
        print_all_schemas()
    elif model_lower == "feature":
        print_feature_schema()
    elif model_lower == "epic":
        print_epic_schema()
    elif model_lower in ("userstory", "user_story"):
        print_user_story_schema()
    elif model_lower == "risk":
        print_risk_schema()
    elif model_lower in ("index", "requirementsindex"):
        print_index_schema()
    else:
        print(f"Unknown model: {model}")
        print("Available: Feature, Epic, UserStory, Risk, Index, All")
        return 1

    return 0


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(description="Show schema documentation")
    parser.add_argument(
        "--model", "-m",
        choices=["Feature", "Epic", "UserStory", "Risk", "Index", "All"],
        default="All",
        help="Model to show (default: All)",
    )
    args = parser.parse_args()
    sys.exit(story_schema_command(args.model))


if __name__ == "__main__":
    main()
