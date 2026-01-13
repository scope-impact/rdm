"""
Check for duplicate story IDs using DuckDB sync.

Usage:
    rdm story check-ids
    rdm story check-ids --explain

Exit code 0 = no duplicates, 1 = duplicates found
"""

from __future__ import annotations

import sys
from pathlib import Path

from rdm.story_audit.sync import extract_data


def find_duplicates(data: dict[str, list[dict]]) -> dict[str, list[str]]:
    """Find duplicate IDs across all entity types.

    Returns:
        Dict of id -> list of source locations (e.g., ["FT-001.yaml", "FT-002.yaml"])
    """
    try:
        import duckdb
    except ImportError:
        # Fallback to pure Python if duckdb not available
        return _find_duplicates_python(data)

    conn = duckdb.connect(":memory:")

    # Create a unified view of all IDs
    conn.execute("""
        CREATE TABLE all_ids (
            id VARCHAR,
            type VARCHAR,
            source VARCHAR
        )
    """)

    # Insert all IDs
    for f in data["features"]:
        conn.execute("INSERT INTO all_ids VALUES (?, ?, ?)",
                     [f["feature_id"], "feature", f["source_file"]])

    for s in data["user_stories"]:
        conn.execute("INSERT INTO all_ids VALUES (?, ?, ?)",
                     [s["story_id"], "user_story", f"{s['feature_id']} in {s.get('feature_title', '')}"])

    for e in data["epics"]:
        conn.execute("INSERT INTO all_ids VALUES (?, ?, ?)",
                     [e["epic_id"], "epic", "_index.yaml"])

    for r in data["risks"]:
        conn.execute("INSERT INTO all_ids VALUES (?, ?, ?)",
                     [r["risk_id"], "risk", r["source_file"]])

    for rc in data["risk_controls"]:
        conn.execute("INSERT INTO all_ids VALUES (?, ?, ?)",
                     [rc["control_id"], "risk_control", f"risk {rc['risk_id']}"])

    # Find duplicates
    result = conn.execute("""
        SELECT id, LIST(source) as sources
        FROM all_ids
        GROUP BY id
        HAVING COUNT(*) > 1
    """).fetchall()

    conn.close()

    return {row[0]: row[1] for row in result}


def _find_duplicates_python(data: dict[str, list[dict]]) -> dict[str, list[str]]:
    """Pure Python fallback for finding duplicates."""
    from collections import defaultdict

    id_sources: dict[str, list[str]] = defaultdict(list)

    for f in data["features"]:
        id_sources[f["feature_id"]].append(f["source_file"])

    for s in data["user_stories"]:
        id_sources[s["story_id"]].append(f"{s['feature_id']}")

    for e in data["epics"]:
        id_sources[e["epic_id"]].append("_index.yaml")

    for r in data["risks"]:
        id_sources[r["risk_id"]].append(r["source_file"])

    for rc in data["risk_controls"]:
        id_sources[rc["control_id"]].append(f"risk {rc['risk_id']}")

    return {k: v for k, v in id_sources.items() if len(v) > 1}


def story_check_ids_command(
    requirements_dir: Path | None = None,
    explain: bool = False,
) -> int:
    """Run story check-ids command.

    Args:
        requirements_dir: Path to requirements directory
        explain: Show detailed context

    Returns:
        0 if no duplicates, 1 if duplicates found
    """
    yaml_dir = (requirements_dir or Path("requirements")).resolve()

    if not yaml_dir.exists():
        print(f"Error: Requirements directory not found: {yaml_dir}")
        return 1

    # Use sync's extract_data to parse everything
    try:
        data = extract_data(yaml_dir)
    except Exception as e:
        print(f"Error parsing requirements: {e}")
        return 1

    # Count total IDs
    total_ids = (
        len(data["features"]) +
        len(data["user_stories"]) +
        len(data["epics"]) +
        len(data["risks"]) +
        len(data["risk_controls"])
    )

    # Find duplicates
    duplicates = find_duplicates(data)

    if duplicates:
        print("Duplicate IDs found:\n")
        for dup_id, sources in sorted(duplicates.items()):
            print(f"  {dup_id}:")
            for src in sources:
                print(f"    - {src}")

        if explain:
            print("\nTo fix: ensure each ID is unique across all requirements files.")

        print(f"\n{len(duplicates)} duplicate ID(s) found.")
        return 1

    print(f"No duplicate IDs found ({total_ids} unique IDs checked)")
    return 0


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(description="Check for duplicate story IDs")
    parser.add_argument("-r", "--requirements", type=Path, help="Requirements directory")
    parser.add_argument("--explain", action="store_true", help="Show detailed context")
    args = parser.parse_args()

    sys.exit(story_check_ids_command(args.requirements, explain=args.explain))


if __name__ == "__main__":
    main()
