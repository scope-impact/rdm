"""
Check for duplicate story IDs - for use in pre-commit hooks.

Usage:
    rdm story check-ids [files...]

Exit code 0 = no duplicates, 1 = duplicates found
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path


# Matches ID definitions like "id: US-001" or "id: FT-001"
ID_PATTERN = re.compile(r"\bid:\s*((?:FT|US|EP|DC|GR)-\d{3})\b")


def find_id_definitions(file_path: Path) -> list[tuple[str, int]]:
    """Find story ID definitions (id: XX-XXX) in a file.

    Returns:
        List of (story_id, line_number) tuples
    """
    definitions = []
    try:
        content = file_path.read_text(encoding="utf-8", errors="ignore")
        for i, line in enumerate(content.splitlines(), 1):
            for match in ID_PATTERN.finditer(line):
                definitions.append((match.group(1), i))
    except Exception:
        pass
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
# CLI ENTRY POINT
# =============================================================================


def story_check_ids_command(files: list[Path] | None = None) -> int:
    """Run story check-ids command.

    Args:
        files: Optional list of files to check. If None, checks requirements/

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
        return 1

    # Count unique IDs
    id_locations: dict[str, list] = defaultdict(list)
    for file_path in yaml_files:
        for story_id, _ in find_id_definitions(file_path):
            id_locations[story_id].append(file_path)

    print(f"No duplicate IDs found ({len(id_locations)} unique IDs checked)")
    return 0


def main() -> None:
    """CLI entry point."""
    files = [Path(f) for f in sys.argv[1:]] if len(sys.argv) > 1 else None
    sys.exit(story_check_ids_command(files))


if __name__ == "__main__":
    main()
