"""
Validate requirements YAML files against the Pydantic schema.

Usage:
    rdm story validate [--strict] [--verbose]
    rdm story validate --file path/to/feature.yaml
    rdm story validate --suggest-fixes
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

try:
    import yaml
    from pydantic import ValidationError
except ImportError as e:
    raise ImportError(
        f"Missing dependency: {e}. Install with: pip install rdm[story-audit]"
    )

from rdm.story_audit.schema import (
    SCHEMA_VERSION,
    Feature,
    RequirementsIndex,
    FEATURE_ID_PATTERN,
    USER_STORY_ID_PATTERN,
    EPIC_ID_PATTERN,
)


# =============================================================================
# ERROR HINTS - Context-aware error messages
# =============================================================================

# Common field mistakes and their hints
FIELD_HINTS: dict[str, dict[str, str]] = {
    "id": {
        "pattern": "ID must match the expected pattern",
        "examples": {
            "FT": "FT-001, FT-002, FT-123",
            "US": "US-001, US-AUTH-001",
            "EP": "EP-001, EP-002",
            "RSK": "RSK-001",
            "RC": "RC-001",
        },
    },
    "epic_id": {
        "pattern": EPIC_ID_PATTERN,
        "hint": "Use 'epic_id:' to reference an epic, e.g., epic_id: EP-001",
    },
    "features": {
        "hint": "Use list of feature IDs: features: [FT-001, FT-002]",
    },
}

# Common mistakes and their fixes
COMMON_MISTAKES: dict[str, dict[str, str]] = {
    "ref": {
        "context": "FeatureRef or EpicRef",
        "problem": "'ref:' is not a supported field",
        "fix": "Use 'id:' for definitions, not 'ref:'",
        "example_wrong": "ref: FT-001",
        "example_correct": "id: FT-001",
    },
    "story_id": {
        "context": "UserStory",
        "problem": "'story_id:' is not a supported field",
        "fix": "Use 'id:' instead of 'story_id:'",
        "example_wrong": "story_id: US-001",
        "example_correct": "id: US-001",
    },
    "feature_id": {
        "context": "Feature",
        "problem": "'feature_id:' is not a supported field",
        "fix": "Use 'id:' instead of 'feature_id:'",
        "example_wrong": "feature_id: FT-001",
        "example_correct": "id: FT-001",
    },
}


def format_validation_error(
    err: dict[str, Any],
    raw_data: dict[str, Any] | None = None,
    file_path: Path | None = None,
) -> tuple[str, str | None]:
    """Format a Pydantic validation error with helpful hints.

    Args:
        err: Pydantic error dictionary
        raw_data: The raw YAML data that failed validation
        file_path: Path to the file being validated

    Returns:
        Tuple of (error_message, hint_message or None)
    """
    loc = ".".join(str(x) for x in err["loc"])
    msg = err["msg"]
    error_type = err.get("type", "")

    # Build base error message
    error_msg = f"{loc}: {msg}"
    hint_msg = None

    # Add context-specific hints
    field_name = err["loc"][-1] if err["loc"] else None

    # Check for ID pattern errors
    if field_name == "id" and "pattern" in error_type.lower():
        # Determine expected pattern from location
        if any("user_stories" in str(loc_part) for loc_part in err["loc"]):
            hint_msg = "  Expected: id: US-XXX (e.g., US-001 or US-AUTH-001)\n"
            hint_msg += f"  Pattern: {USER_STORY_ID_PATTERN}"
        elif file_path and "features" in str(file_path):
            hint_msg = "  Expected: id: FT-XXX (e.g., FT-001)\n"
            hint_msg += f"  Pattern: {FEATURE_ID_PATTERN}"
        elif file_path and "epics" in str(file_path):
            hint_msg = "  Expected: id: EP-XXX (e.g., EP-001)\n"
            hint_msg += f"  Pattern: {EPIC_ID_PATTERN}"

        # Show what was provided
        if raw_data:
            actual_value = _get_nested_value(raw_data, err["loc"])
            if actual_value:
                hint_msg = f"  Got: {actual_value}\n" + (hint_msg or "")

    # Check for required field errors
    elif "required" in error_type.lower() or "missing" in msg.lower():
        if field_name == "id":
            # Check if they used a wrong field name
            if raw_data:
                parent_data = _get_nested_value(raw_data, err["loc"][:-1])
                if parent_data and isinstance(parent_data, dict):
                    for wrong_name in ["ref", "story_id", "feature_id", "epic_id"]:
                        if wrong_name in parent_data and wrong_name != "epic_id":
                            mistake = COMMON_MISTAKES.get(wrong_name, {})
                            hint_msg = f"  Got: {wrong_name}: {parent_data[wrong_name]}\n"
                            hint_msg += f"  Hint: {mistake.get('fix', 'Use id: for definitions')}"
                            break

        if hint_msg is None and field_name:
            # Provide general hint for required fields
            if field_name == "id":
                hint_msg = "  Hint: Every definition requires an 'id:' field"
            elif field_name == "title":
                hint_msg = "  Hint: Every feature/epic requires a 'title:' field"

    # Check for type errors with features list
    elif field_name == "features" and "list" in msg.lower():
        hint_msg = "  Expected: features: [FT-001, FT-002, FT-003]\n"
        hint_msg += "  Hint: Use a simple list of IDs, not objects"

    return error_msg, hint_msg


def _get_nested_value(data: dict[str, Any], path: tuple) -> Any:
    """Get a nested value from a dictionary using a path tuple."""
    try:
        result = data
        for key in path:
            if isinstance(result, dict):
                result = result.get(key)
            elif isinstance(result, list) and isinstance(key, int):
                result = result[key] if key < len(result) else None
            else:
                return None
        return result
    except (KeyError, IndexError, TypeError):
        return None


# =============================================================================
# FIX SUGGESTIONS
# =============================================================================


@dataclass
class FixSuggestion:
    """A suggested fix for a validation issue."""

    level: str  # "error" or "warn"
    file_path: str
    message: str
    suggestion: str
    current: str | None = None
    expected: str | None = None


def analyze_for_fixes(
    yaml_dir: Path,
) -> list[FixSuggestion]:
    """Analyze requirements directory for common issues and suggest fixes.

    Args:
        yaml_dir: Path to requirements directory

    Returns:
        List of fix suggestions
    """
    suggestions: list[FixSuggestion] = []

    # Check _index.yaml for common issues
    index_path = yaml_dir / "_index.yaml"
    if index_path.exists():
        suggestions.extend(_analyze_index_for_fixes(index_path, yaml_dir))

    # Check epics directory
    epics_dir = yaml_dir / "epics"
    if epics_dir.exists():
        suggestions.extend(_analyze_epics_for_fixes(epics_dir))

    # Check features directory
    features_dir = yaml_dir / "features"
    if features_dir.exists():
        suggestions.extend(_analyze_features_for_fixes(features_dir))

    return suggestions


def _analyze_index_for_fixes(index_path: Path, yaml_dir: Path) -> list[FixSuggestion]:
    """Analyze _index.yaml for issues."""
    suggestions = []

    try:
        with open(index_path) as f:
            data = yaml.safe_load(f) or {}

        # Check if epics section duplicates epics/*.yaml
        if "epics" in data and data["epics"]:
            epics_dir = yaml_dir / "epics"
            if epics_dir.exists() and list(epics_dir.glob("EP-*.yaml")):
                suggestions.append(FixSuggestion(
                    level="warn",
                    file_path=str(index_path),
                    message="epics section may duplicate epics/*.yaml definitions",
                    suggestion="Remove epics from _index.yaml, keep only in epics/*.yaml",
                    current="epics: [{id: EP-001, ...}]",
                    expected="# epics defined in epics/*.yaml",
                ))

        # Check if features section uses object format
        if "features" in data and data["features"]:
            for i, feat in enumerate(data["features"]):
                if isinstance(feat, dict):
                    # Check for ref field (common mistake)
                    if "ref" in feat:
                        suggestions.append(FixSuggestion(
                            level="error",
                            file_path=str(index_path),
                            message=f"features[{i}] uses 'ref:' which is not supported",
                            suggestion="Use 'id:' instead of 'ref:' for FeatureRef",
                            current=f"- ref: {feat.get('ref')}",
                            expected=f"- id: {feat.get('ref')}",
                        ))

    except Exception:
        pass

    return suggestions


def _analyze_epics_for_fixes(epics_dir: Path) -> list[FixSuggestion]:
    """Analyze epic files for issues."""
    suggestions = []

    for epic_path in epics_dir.glob("EP-*.yaml"):
        try:
            with open(epic_path) as f:
                data = yaml.safe_load(f) or {}

            # Check if features uses object format
            if "features" in data and data["features"]:
                for i, feat in enumerate(data["features"]):
                    if isinstance(feat, dict):
                        suggestions.append(FixSuggestion(
                            level="warn",
                            file_path=str(epic_path),
                            message=f"features[{i}] uses object format, expected list of IDs",
                            suggestion="Use simple ID list: features: [FT-001, FT-002]",
                            current=f"features: [{{ref: {feat.get('ref', feat.get('id', '...'))}}}]",
                            expected="features: [FT-001, FT-002, FT-003]",
                        ))
                        break  # Only report once per file

        except Exception:
            pass

    return suggestions


def _analyze_features_for_fixes(features_dir: Path) -> list[FixSuggestion]:
    """Analyze feature files for issues."""
    suggestions = []

    for feature_path in features_dir.glob("FT-*.yaml"):
        try:
            with open(feature_path) as f:
                data = yaml.safe_load(f) or {}

            # Check for common mistakes
            if "feature_id" in data and "id" not in data:
                suggestions.append(FixSuggestion(
                    level="error",
                    file_path=str(feature_path),
                    message="Uses 'feature_id:' instead of 'id:'",
                    suggestion="Rename 'feature_id:' to 'id:'",
                    current=f"feature_id: {data['feature_id']}",
                    expected=f"id: {data['feature_id']}",
                ))

            # Check user_stories for common mistakes
            if "user_stories" in data:
                for i, story in enumerate(data.get("user_stories", [])):
                    if isinstance(story, dict):
                        if "story_id" in story and "id" not in story:
                            suggestions.append(FixSuggestion(
                                level="error",
                                file_path=str(feature_path),
                                message=f"user_stories[{i}] uses 'story_id:' instead of 'id:'",
                                suggestion="Rename 'story_id:' to 'id:'",
                                current=f"story_id: {story['story_id']}",
                                expected=f"id: {story['story_id']}",
                            ))

        except Exception:
            pass

    return suggestions


def print_fix_suggestions(suggestions: list[FixSuggestion]) -> None:
    """Print fix suggestions in a readable format."""
    if not suggestions:
        print("\nNo fix suggestions - files look good!")
        return

    print("\n" + "=" * 60)
    print("FIX SUGGESTIONS")
    print("=" * 60)

    for suggestion in suggestions:
        level_str = "[ERROR]" if suggestion.level == "error" else "[WARN] "
        print(f"\n{level_str} {suggestion.file_path}: {suggestion.message}")
        print(f"  Suggestion: {suggestion.suggestion}")
        if suggestion.current:
            print(f"  Current:  {suggestion.current}")
        if suggestion.expected:
            print(f"  Expected: {suggestion.expected}")


# =============================================================================
# VALIDATION RESULT TYPES
# =============================================================================


@dataclass
class ValidationResult:
    """Result of validating a single file."""

    file_path: Path
    valid: bool
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    extra_fields: dict[str, list[str]] = field(default_factory=dict)
    stats: dict[str, int] = field(default_factory=dict)


@dataclass
class ValidationSummary:
    """Summary of all validation results."""

    total_files: int
    valid_files: int
    invalid_files: int
    total_errors: int
    total_warnings: int
    total_extra_fields: int
    results: list[ValidationResult] = field(default_factory=list)


# =============================================================================
# VALIDATION FUNCTIONS
# =============================================================================


def validate_index(yaml_dir: Path) -> ValidationResult:
    """Validate the _index.yaml file."""
    index_path = yaml_dir / "_index.yaml"
    errors: list[str] = []
    warnings: list[str] = []
    extra_fields: dict[str, list[str]] = {}
    stats: dict[str, int] = {}

    if not index_path.exists():
        return ValidationResult(
            file_path=index_path,
            valid=False,
            errors=["File not found"],
            warnings=[],
            extra_fields={},
            stats={},
        )

    try:
        with open(index_path) as f:
            data = yaml.safe_load(f)

        index = RequirementsIndex(**data)

        # Collect stats
        stats["phases"] = len(index.phases)
        stats["epics"] = len(index.epics)
        stats["feature_refs"] = len(index.features)

        # Check for duplicate epic IDs
        epic_ids = [e.id for e in index.epics]
        duplicates = [eid for eid in epic_ids if epic_ids.count(eid) > 1]
        if duplicates:
            errors.append(f"Duplicate epic IDs: {set(duplicates)}")

        # Check for features referenced in phases but not defined
        all_phase_features = set()
        for phase in index.phases.values():
            all_phase_features.update(phase.features)

        # Warnings for missing descriptions
        for phase_id, phase in index.phases.items():
            if not phase.description:
                warnings.append(f"Phase '{phase_id}' has no description")

    except ValidationError as e:
        for err in e.errors():
            error_msg, hint_msg = format_validation_error(err, data, index_path)
            errors.append(error_msg)
            if hint_msg:
                errors.append(hint_msg)

    except Exception as e:
        errors.append(f"Parse error: {e}")

    return ValidationResult(
        file_path=index_path,
        valid=len(errors) == 0,
        errors=errors,
        warnings=warnings,
        extra_fields=extra_fields,
        stats=stats,
    )


def validate_feature(feature_path: Path, strict: bool = False) -> ValidationResult:
    """Validate a single feature YAML file."""
    errors: list[str] = []
    warnings: list[str] = []
    extra_fields: dict[str, list[str]] = {}
    stats: dict[str, int] = {}

    if not feature_path.exists():
        return ValidationResult(
            file_path=feature_path,
            valid=False,
            errors=["File not found"],
            warnings=[],
            extra_fields={},
            stats={},
        )

    try:
        with open(feature_path) as f:
            data = yaml.safe_load(f)

        feature = Feature(**data)

        # Collect stats
        stats["user_stories"] = len(feature.user_stories)
        stats["acceptance_criteria"] = sum(
            len(s.acceptance_criteria) for s in feature.user_stories
        )
        stats["dod_items"] = (
            len(feature.definition_of_done)
            if isinstance(feature.definition_of_done, list)
            else sum(len(v) for v in feature.definition_of_done.values())
        )

        # Check for extra fields in feature
        feature_extra = feature.get_extra_fields()
        if feature_extra:
            extra_fields[feature.id] = list(feature_extra.keys())
            if strict:
                errors.append(f"Extra fields in feature: {list(feature_extra.keys())}")

        # Validate user stories
        story_ids = []
        for story in feature.user_stories:
            story_ids.append(story.id)

            # Check for extra fields in story
            story_extra = story.get_extra_fields()
            if story_extra:
                extra_fields[story.id] = list(story_extra.keys())
                if strict:
                    errors.append(
                        f"Extra fields in {story.id}: {list(story_extra.keys())}"
                    )

            # Warnings for incomplete stories
            if not story.as_a:
                warnings.append(f"{story.id}: Missing 'as_a' field")
            if not story.i_want:
                warnings.append(f"{story.id}: Missing 'i_want' field")
            if not story.so_that:
                warnings.append(f"{story.id}: Missing 'so_that' field")
            if not story.acceptance_criteria:
                warnings.append(f"{story.id}: No acceptance criteria")

        # Check for duplicate story IDs within feature
        duplicates = [sid for sid in story_ids if story_ids.count(sid) > 1]
        if duplicates:
            errors.append(f"Duplicate story IDs: {set(duplicates)}")

        # Warnings for missing feature fields
        if not feature.description:
            warnings.append("Feature has no description")
        if not feature.business_value:
            warnings.append("Feature has no business_value")

    except ValidationError as e:
        for err in e.errors():
            error_msg, hint_msg = format_validation_error(err, data, feature_path)
            errors.append(error_msg)
            if hint_msg:
                errors.append(hint_msg)

    except Exception as e:
        errors.append(f"Parse error: {e}")

    return ValidationResult(
        file_path=feature_path,
        valid=len(errors) == 0,
        errors=errors,
        warnings=warnings,
        extra_fields=extra_fields,
        stats=stats,
    )


def validate_all(yaml_dir: Path, strict: bool = False) -> ValidationSummary:
    """Validate all YAML files in the requirements directory."""
    results: list[ValidationResult] = []

    # Validate index
    index_result = validate_index(yaml_dir)
    results.append(index_result)

    # Validate all features
    for feature_path in sorted(yaml_dir.glob("features/FT-*.yaml")):
        result = validate_feature(feature_path, strict=strict)
        results.append(result)

    # Compute summary
    valid_files = sum(1 for r in results if r.valid)
    invalid_files = sum(1 for r in results if not r.valid)
    total_errors = sum(len(r.errors) for r in results)
    total_warnings = sum(len(r.warnings) for r in results)
    total_extra = sum(len(r.extra_fields) for r in results)

    return ValidationSummary(
        total_files=len(results),
        valid_files=valid_files,
        invalid_files=invalid_files,
        total_errors=total_errors,
        total_warnings=total_warnings,
        total_extra_fields=total_extra,
        results=results,
    )


# =============================================================================
# OUTPUT FORMATTING
# =============================================================================


def print_result(result: ValidationResult, verbose: bool = False) -> None:
    """Print a single validation result."""
    status = "PASS" if result.valid else "FAIL"

    print(f"[{status}] {result.file_path.name}")

    if result.errors:
        for error in result.errors:
            print(f"       ERROR: {error}")

    if verbose or not result.valid:
        for warning in result.warnings:
            print(f"       WARN:  {warning}")

        for obj_id, fields in result.extra_fields.items():
            print(f"       EXTRA: {obj_id} has fields: {fields}")

    if verbose and result.stats:
        stats_str = ", ".join(f"{k}={v}" for k, v in result.stats.items())
        print(f"       STATS: {stats_str}")


def print_summary(summary: ValidationSummary) -> None:
    """Print validation summary."""
    print("\n" + "=" * 60)
    print("VALIDATION SUMMARY")
    print("=" * 60)
    print(f"""
  Schema Version:    v{SCHEMA_VERSION}
  Files Checked:     {summary.total_files}
  Valid:             {summary.valid_files}
  Invalid:           {summary.invalid_files}
  Errors:            {summary.total_errors}
  Warnings:          {summary.total_warnings}
  Extra Fields:      {summary.total_extra_fields}
""")

    if summary.invalid_files == 0:
        print("All files passed validation!")
    else:
        print(
            f"Found {summary.total_errors} error(s) in {summary.invalid_files} file(s)"
        )

    print("=" * 60)


# =============================================================================
# CLI ENTRY POINT
# =============================================================================


def story_validate_command(
    requirements_dir: Path | None = None,
    file_path: Path | None = None,
    strict: bool = False,
    verbose: bool = False,
    quiet: bool = False,
    suggest_fixes: bool = False,
) -> int:
    """Run story validate command.

    Args:
        requirements_dir: Directory containing requirements YAML files
        file_path: Single file to validate
        strict: Fail on extra fields
        verbose: Show warnings for passing files
        quiet: Only show summary
        suggest_fixes: Show fix suggestions

    Returns:
        0 if valid, 1 if errors, 2 if setup error
    """
    print(f"Schema Version: v{SCHEMA_VERSION}")

    # Single file validation
    if file_path:
        if not file_path.exists():
            print(f"Error: File not found: {file_path}")
            return 2

        result = validate_feature(file_path, strict=strict)
        print_result(result, verbose=verbose)
        return 0 if result.valid else 1

    # Validate all files
    yaml_dir = (requirements_dir or Path("requirements")).resolve()
    if not yaml_dir.exists():
        print(f"Error: Requirements directory not found: {yaml_dir}")
        return 2

    print(f"Validating: {yaml_dir}\n")

    summary = validate_all(yaml_dir, strict=strict)

    if not quiet:
        for result in summary.results:
            print_result(result, verbose=verbose)

    print_summary(summary)

    # Show fix suggestions if requested
    if suggest_fixes:
        suggestions = analyze_for_fixes(yaml_dir)
        print_fix_suggestions(suggestions)

    return 0 if summary.invalid_files == 0 else 1


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(
        description="Validate requirements YAML files against schema"
    )
    parser.add_argument(
        "--requirements",
        "-r",
        type=Path,
        default=Path("requirements"),
        help="Path to requirements directory (default: ./requirements)",
    )
    parser.add_argument(
        "--file", "-f", type=Path, help="Validate a single file instead of all files"
    )
    parser.add_argument(
        "--strict",
        "-s",
        action="store_true",
        help="Fail on extra fields not in schema",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Show warnings and extra fields for passing files",
    )
    parser.add_argument(
        "--quiet",
        "-q",
        action="store_true",
        help="Only show summary, not individual file results",
    )
    parser.add_argument(
        "--suggest-fixes",
        action="store_true",
        help="Show suggestions for fixing common issues",
    )
    args = parser.parse_args()

    sys.exit(
        story_validate_command(
            requirements_dir=args.requirements,
            file_path=args.file,
            strict=args.strict,
            verbose=args.verbose,
            quiet=args.quiet,
            suggest_fixes=args.suggest_fixes,
        )
    )


if __name__ == "__main__":
    main()
