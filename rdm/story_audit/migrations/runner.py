"""
Migration runner for DuckDB schema versioning.

Handles:
- Tracking schema version in schema_version table
- Running pending migrations in order
- Supporting both up() migrations
"""

from __future__ import annotations

from importlib import import_module
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import duckdb

MIGRATIONS_DIR = Path(__file__).parent


def get_current_version(conn: "duckdb.DuckDBPyConnection") -> str | None:
    """Get current schema version from database.

    Args:
        conn: DuckDB connection

    Returns:
        Current version string (e.g., "001") or None if no migrations applied
    """
    try:
        result = conn.execute(
            "SELECT version FROM schema_version ORDER BY applied_at DESC LIMIT 1"
        ).fetchone()
        return result[0] if result else None
    except Exception:
        return None


def list_migrations() -> list[Path]:
    """List all migration files in order.

    Migration files must be named: NNN_description.py
    Where NNN is a 3-digit number (e.g., 001, 002, 003)

    Returns:
        Sorted list of migration file paths
    """
    return sorted(MIGRATIONS_DIR.glob("[0-9][0-9][0-9]_*.py"))


def run_migrations(
    conn: "duckdb.DuckDBPyConnection",
    target_version: str | None = None,
) -> list[str]:
    """Run pending migrations up to target version.

    Args:
        conn: DuckDB connection
        target_version: Optional version to migrate to (e.g., "002").
                       If None, runs all pending migrations.

    Returns:
        List of applied migration versions
    """
    current = get_current_version(conn)
    applied = []

    migration_files = list_migrations()

    for mig_file in migration_files:
        version = mig_file.stem.split("_")[0]  # "001" from "001_initial.py"

        # Skip already applied
        if current and version <= current:
            continue

        # Stop if we've reached target
        if target_version and version > target_version:
            break

        print(f"Applying migration {mig_file.name}...")

        # Import and run the migration
        module_name = f"rdm.story_audit.migrations.{mig_file.stem}"
        module = import_module(module_name)
        module.up(conn)

        # Record the migration
        conn.execute(
            "INSERT INTO schema_version (version) VALUES (?)",
            [version],
        )
        applied.append(version)

    return applied


def ensure_schema_version_table(conn: "duckdb.DuckDBPyConnection") -> None:
    """Create schema_version table if it doesn't exist.

    Args:
        conn: DuckDB connection
    """
    conn.execute("""
        CREATE TABLE IF NOT EXISTS schema_version (
            version VARCHAR PRIMARY KEY,
            applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
