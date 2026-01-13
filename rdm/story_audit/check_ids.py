"""
Check for duplicate story IDs - for use in pre-commit hooks.

Usage:
    rdm story check-ids [files...]
    rdm story check-ids --explain

Exit code 0 = no duplicates, 1 = duplicates found
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

from rdm.story_audit.schema import ID_DEFINITION_PATTERN, ID_PATTERN


# =============================================================================
# LOCATION TRACKING
# =============================================================================


@dataclass
class IDLocation:
    """Location where an ID is found."""

    file_path: str
    line_number: int
    location_type: Literal["definition", "reference"]
    context: str = ""  # e.g., "FeatureRef.id", "Epic.features", etc.


@dataclass
class IDAnalysis:
    """Analysis of a single ID across the codebase."""

    story_id: str
    definitions: list[IDLocation] = field(default_factory=list)
    references: list[IDLocation] = field(default_factory=list)
    has_conflict: bool = False
    warnings: list[str] = field(default_factory=list)


def find_id_definitions(file_path: Path) -> list[tuple[str, int]]:
    """Find story ID definitions (id: XX-XXX) in a file.

    Returns:
        List of (story_id, line_number) tuples
    """
    definitions = []
    try:
        content = file_path.read_text(encoding="utf-8", errors="ignore")
        for i, line in enumerate(content.splitlines(), 1):
            for match in ID_DEFINITION_PATTERN.finditer(line):
                definitions.append((match.group(1), i))
    except Exception as e:
        print(f"Warning: Could not read or parse {file_path}: {e}", file=sys.stderr)
    return definitions


def check_for_duplicates(files: list[Path]) -> dict[str, list[tuple[str, int]]]:
    """Check files for duplicate ID definitions.

    Args:
        files: List of YAML files to check

    Returns:
        Dict of story_id -> list of (file_path, line_number) for duplicates only
    """
    id_locations: dict[str, list[tuple[str, int]]] = defaultdict(list)

    for file_path in files:
        if not file_path.exists():
            continue
        for story_id, line_num in find_id_definitions(file_path):
            id_locations[story_id].append((str(file_path), line_num))

    # Filter to only duplicates
    return {k: v for k, v in id_locations.items() if len(v) > 1}


def print_duplicates(duplicates: dict[str, list[tuple[str, int]]]) -> None:
    """Print duplicate IDs in a readable format."""
    print("Duplicate story IDs found:\n")
    for story_id, locations in sorted(duplicates.items()):
        print(f"  {story_id}:")
        for file_path, line_num in locations:
            print(f"    - {file_path}:{line_num}")
    print(f"\n{len(duplicates)} duplicate ID(s) found. Please resolve conflicts.")


# =============================================================================
# DETAILED ANALYSIS (--explain mode)
# =============================================================================

# Pattern to detect references in features: [FT-001, FT-002] or features: [FT-001]
FEATURES_LIST_PATTERN = re.compile(r"features:\s*\[([^\]]*)\]")
EPIC_ID_REF_PATTERN = re.compile(r"epic_id:\s*([A-Z]+-\d+)")


def find_id_references(file_path: Path) -> list[tuple[str, int, str]]:
    """Find ID references (not definitions) in a file.

    Looks for:
    - features: [FT-001, FT-002]
    - epic_id: EP-001

    Returns:
        List of (story_id, line_number, context) tuples
    """
    references = []
    try:
        content = file_path.read_text(encoding="utf-8", errors="ignore")
        for i, line in enumerate(content.splitlines(), 1):
            # Check for features list references
            match = FEATURES_LIST_PATTERN.search(line)
            if match:
                ids_str = match.group(1)
                for id_match in ID_PATTERN.finditer(ids_str):
                    references.append((id_match.group(0), i, "features list"))

            # Check for epic_id references
            match = EPIC_ID_REF_PATTERN.search(line)
            if match:
                # Don't add if this is a definition context
                if "id:" not in line or "epic_id:" in line:
                    references.append((match.group(1), i, "epic_id reference"))

    except Exception as e:
        print(f"Warning: Could not read {file_path}: {e}", file=sys.stderr)

    return references


def analyze_ids_detailed(files: list[Path]) -> dict[str, IDAnalysis]:
    """Perform detailed analysis of all IDs in files.

    Args:
        files: List of YAML files to analyze

    Returns:
        Dict of story_id -> IDAnalysis
    """
    analysis: dict[str, IDAnalysis] = defaultdict(lambda: IDAnalysis(story_id=""))

    for file_path in files:
        if not file_path.exists():
            continue

        file_str = str(file_path)

        # Find definitions
        for story_id, line_num in find_id_definitions(file_path):
            if analysis[story_id].story_id == "":
                analysis[story_id].story_id = story_id

            # Determine context from file path
            context = _get_definition_context(file_path, story_id)

            analysis[story_id].definitions.append(IDLocation(
                file_path=file_str,
                line_number=line_num,
                location_type="definition",
                context=context,
            ))

        # Find references
        for story_id, line_num, ref_context in find_id_references(file_path):
            if analysis[story_id].story_id == "":
                analysis[story_id].story_id = story_id

            analysis[story_id].references.append(IDLocation(
                file_path=file_str,
                line_number=line_num,
                location_type="reference",
                context=ref_context,
            ))

    # Analyze for conflicts and warnings
    for story_id, data in analysis.items():
        data.has_conflict = len(data.definitions) > 1

        # Check for potential issues
        if data.has_conflict:
            # Check if conflict is due to FeatureRef using 'id:'
            index_defs = [d for d in data.definitions if "_index.yaml" in d.file_path]
            other_defs = [d for d in data.definitions if "_index.yaml" not in d.file_path]

            if index_defs and other_defs:
                data.warnings.append(
                    "FeatureRef in _index.yaml uses 'id:' which triggers duplicate detection"
                )

    return dict(analysis)


def _get_definition_context(file_path: Path, story_id: str) -> str:
    """Get context description for where an ID is defined."""
    path_str = str(file_path)

    if "_index.yaml" in path_str:
        if story_id.startswith("FT-"):
            return "FeatureRef in _index.yaml"
        elif story_id.startswith("EP-"):
            return "Epic in _index.yaml"
        return "_index.yaml"
    elif "/features/" in path_str or path_str.startswith("features/"):
        if story_id.startswith("US-"):
            return "UserStory in feature file"
        return "Feature definition"
    elif "/epics/" in path_str or path_str.startswith("epics/"):
        return "Epic definition"
    elif "/risks/" in path_str or path_str.startswith("risks/"):
        if story_id.startswith("RC-"):
            return "RiskControl definition"
        return "Risk definition"

    return "YAML file"


def print_explain_analysis(analysis: dict[str, IDAnalysis]) -> None:
    """Print detailed analysis in --explain format."""
    # Filter to IDs with interesting information
    interesting = {
        sid: data for sid, data in analysis.items()
        if data.has_conflict or len(data.definitions) + len(data.references) > 1
    }

    if not interesting:
        print("\nNo conflicts or cross-references found.")
        return

    print("\n" + "=" * 60)
    print("ID ANALYSIS (--explain)")
    print("=" * 60)

    # Print conflicts first
    conflicts = {sid: data for sid, data in interesting.items() if data.has_conflict}
    if conflicts:
        print("\nCONFLICTS (duplicate definitions):")
        print("-" * 40)
        for story_id, data in sorted(conflicts.items()):
            print(f"\n{story_id} found in {len(data.definitions)} locations:")
            for loc in data.definitions:
                print(f"  [DEFINITION] {loc.file_path}:{loc.line_number}")
                if loc.context:
                    print(f"               ({loc.context})")
            for loc in data.references:
                print(f"  [REFERENCE]  {loc.file_path}:{loc.line_number}")
                if loc.context:
                    print(f"               ({loc.context})")

            if data.warnings:
                print()
                for warning in data.warnings:
                    print(f"  Warning: {warning}")

                print("\n  Fix options:")
                print("    1. Remove duplicate definition from _index.yaml (recommended)")
                print("    2. Keep definitions only in dedicated files (features/*.yaml, epics/*.yaml)")

    # Print cross-referenced IDs
    crossrefs = {
        sid: data for sid, data in interesting.items()
        if not data.has_conflict and data.references
    }
    if crossrefs:
        print("\n\nCROSS-REFERENCES:")
        print("-" * 40)
        for story_id, data in sorted(crossrefs.items()):
            print(f"\n{story_id}:")
            for loc in data.definitions:
                print(f"  [DEFINITION] {loc.file_path}:{loc.line_number} ({loc.context})")
            for loc in data.references:
                print(f"  [REFERENCE]  {loc.file_path}:{loc.line_number} ({loc.context})")


# =============================================================================
# CLI ENTRY POINT
# =============================================================================


def story_check_ids_command(
    files: list[Path] | None = None,
    explain: bool = False,
) -> int:
    """Run story check-ids command.

    Args:
        files: Optional list of files to check. If None, checks requirements/
        explain: If True, show detailed analysis with context

    Returns:
        0 if no duplicates, 1 if duplicates found
    """
    # Get files to check
    if files:
        yaml_files = [f for f in files if f.suffix in (".yaml", ".yml")]
    else:
        # Default: check requirements directory
        req_dir = Path("requirements")
        if not req_dir.exists():
            print("No requirements directory found.")
            return 0
        yaml_files = list(req_dir.rglob("*.yaml"))

    if not yaml_files:
        print("No YAML files to check.")
        return 0

    # Check for duplicates
    duplicates = check_for_duplicates(yaml_files)

    if duplicates:
        print_duplicates(duplicates)

        # Show detailed analysis if --explain is set
        if explain:
            analysis = analyze_ids_detailed(yaml_files)
            print_explain_analysis(analysis)

        return 1

    # Count unique IDs
    id_locations: dict[str, list] = defaultdict(list)
    for file_path in yaml_files:
        for story_id, _ in find_id_definitions(file_path):
            id_locations[story_id].append(file_path)

    print(f"No duplicate IDs found ({len(id_locations)} unique IDs checked)")

    # Show detailed analysis if --explain is set even when no duplicates
    if explain:
        analysis = analyze_ids_detailed(yaml_files)
        print_explain_analysis(analysis)

    return 0


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(
        description="Check for duplicate story IDs"
    )
    parser.add_argument(
        "files",
        nargs="*",
        type=Path,
        help="Files to check (default: requirements/)",
    )
    parser.add_argument(
        "--explain",
        action="store_true",
        help="Show detailed analysis with context for each ID",
    )
    args = parser.parse_args()

    files = args.files if args.files else None
    sys.exit(story_check_ids_command(files, explain=args.explain))


if __name__ == "__main__":
    main()
