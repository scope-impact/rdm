"""
Validate requirements YAML files against the Pydantic schema.

Usage:
    rdm story validate [--strict] [--verbose]
    rdm story validate --file path/to/feature.yaml
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path

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
)


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
            loc = ".".join(str(x) for x in err["loc"])
            errors.append(f"{loc}: {err['msg']}")

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
            loc = ".".join(str(x) for x in err["loc"])
            errors.append(f"{loc}: {err['msg']}")

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
) -> int:
    """Run story validate command."""
    print(f"Schema Version: v{SCHEMA_VERSION}")

    if file_path:
        # Validate single file
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
    args = parser.parse_args()

    sys.exit(
        story_validate_command(
            requirements_dir=args.requirements,
            file_path=args.file,
            strict=args.strict,
            verbose=args.verbose,
            quiet=args.quiet,
        )
    )


if __name__ == "__main__":
    main()
