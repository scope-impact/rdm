"""
Sync Backlog.md to DuckDB for analytics.

Parses markdown files with YAML frontmatter and syncs to DuckDB
with proper schema versioning and migrations.

Usage:
    rdm story sync /path/to/backlog -o out.duckdb
    rdm story sync --migrate-only out.duckdb

Requires: pip install rdm[story-audit]
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import TYPE_CHECKING

from rdm.story_audit.backlog_schema import SCHEMA_VERSION, BacklogData
from rdm.story_audit.backlog_parser import extract_backlog_data
from rdm.story_audit.migrations.runner import (
    ensure_schema_version_table,
    get_current_version,
    run_migrations,
)

if TYPE_CHECKING:
    import duckdb


# =============================================================================
# DATABASE POPULATION
# =============================================================================


def populate_tables(
    conn: "duckdb.DuckDBPyConnection",
    data: BacklogData,
) -> None:
    """Populate all tables with extracted backlog data.

    Args:
        conn: DuckDB connection
        data: Parsed BacklogData object
    """
    project_id = data.project_id

    # Clear existing data for this project
    _clear_project_data(conn, project_id)

    # Insert project
    conn.execute(
        """
        INSERT INTO projects (project_id, task_prefix, project_name, description, repository)
        VALUES (?, ?, ?, ?, ?)
        """,
        [
            project_id,
            data.config.task_prefix,
            data.config.project_name,
            data.config.description,
            data.config.repository,
        ],
    )

    # Track labels for dimension table
    labels_set: set[str] = set()

    # Insert milestones
    for milestone in data.milestones:
        global_id = data.make_global_id(milestone.id)
        task_count = sum(
            1 for t in data.tasks if t.milestone == milestone.id
        )
        conn.execute(
            """
            INSERT INTO milestones
            (global_id, project_id, local_id, title, description, status, task_count, source_file)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                global_id,
                project_id,
                milestone.id,
                milestone.title,
                milestone.description,
                milestone.status,
                task_count,
                None,  # milestone files don't have source_file tracked
            ],
        )
        labels_set.update(milestone.labels)

    # Insert tasks
    for task in data.tasks:
        global_id = data.make_global_id(task.id)
        subtask_count = sum(1 for s in data.subtasks if s.parent_task_id == task.id)
        milestone_global = data.make_global_id(task.milestone) if task.milestone else None

        conn.execute(
            """
            INSERT INTO tasks
            (global_id, project_id, local_id, title, description, business_value,
             status, milestone_id, priority, labels, created_date,
             subtask_count, acceptance_criteria_count, completed_criteria_count, source_file)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                global_id,
                project_id,
                task.id,
                task.title,
                task.description,
                task.business_value,
                task.status,
                milestone_global,
                task.priority,
                task.labels,
                task.created_date,
                subtask_count,
                task.acceptance_criteria_count,
                task.completed_criteria_count,
                task.source_file,
            ],
        )
        labels_set.update(task.labels)

        # Insert acceptance criteria for task
        _insert_acceptance_criteria(conn, project_id, global_id, task.acceptance_criteria)

    # Insert subtasks
    for subtask in data.subtasks:
        global_id = data.make_global_id(subtask.id)
        parent_global = data.make_global_id(subtask.parent_task_id) if subtask.parent_task_id else ""

        conn.execute(
            """
            INSERT INTO subtasks
            (global_id, project_id, local_id, parent_task_id, title, description,
             status, labels, created_date, acceptance_criteria_count, completed_criteria_count, source_file)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                global_id,
                project_id,
                subtask.id,
                parent_global,
                subtask.title,
                subtask.description,
                subtask.status,
                subtask.labels,
                subtask.created_date,
                subtask.acceptance_criteria_count,
                subtask.completed_criteria_count,
                subtask.source_file,
            ],
        )
        labels_set.update(subtask.labels)

        # Insert acceptance criteria for subtask
        _insert_acceptance_criteria(conn, project_id, global_id, subtask.acceptance_criteria)

    # Insert risks
    for risk in data.risks:
        global_id = data.make_global_id(risk.id)

        conn.execute(
            """
            INSERT INTO risks
            (global_id, project_id, local_id, title, stride_category, severity,
             probability, risk_level, cluster, hazard, situation, harm, description,
             mitigation_status, residual_risk, labels, control_count, source_file)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                global_id,
                project_id,
                risk.id,
                risk.title,
                risk.stride_category,
                risk.severity,
                risk.probability,
                risk.risk_level,
                risk.cluster,
                risk.hazard,
                risk.situation,
                risk.harm,
                risk.description,
                risk.mitigation_status,
                risk.residual_risk,
                risk.labels,
                len(risk.controls),
                risk.source_file,
            ],
        )
        labels_set.update(risk.labels)

        # Insert affected requirements
        for req_id in risk.affected_requirements:
            conn.execute(
                """
                INSERT INTO risk_requirements (project_id, risk_id, requirement_id)
                VALUES (?, ?, ?)
                """,
                [project_id, global_id, req_id],
            )

        # Insert controls
        for i, (control_desc, refs) in enumerate(zip(risk.controls, risk.control_refs)):
            conn.execute(
                """
                INSERT INTO risk_controls (project_id, risk_id, description, refs, sort_order)
                VALUES (?, ?, ?, ?, ?)
                """,
                [project_id, global_id, control_desc, refs, i],
            )

    # Insert decisions
    for decision in data.decisions:
        global_id = data.make_global_id(decision.id)

        conn.execute(
            """
            INSERT INTO decisions
            (global_id, project_id, local_id, title, date, status,
             context, decision, rationale, consequences, labels, source_file)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                global_id,
                project_id,
                decision.id,
                decision.title,
                decision.date,
                decision.status,
                decision.context,
                decision.decision,
                decision.rationale,
                decision.consequences,
                decision.labels,
                decision.source_file,
            ],
        )
        labels_set.update(decision.labels)

    # Insert labels dimension
    for label in sorted(labels_set):
        conn.execute(
            """
            INSERT INTO labels (project_id, name)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """,
            [project_id, label],
        )


def _clear_project_data(conn: "duckdb.DuckDBPyConnection", project_id: str) -> None:
    """Clear all existing data for a project.

    Args:
        conn: DuckDB connection
        project_id: Project ID to clear
    """
    tables = [
        "labels",
        "risk_controls",
        "risk_requirements",
        "decisions",
        "risks",
        "acceptance_criteria",
        "subtasks",
        "tasks",
        "milestones",
        "projects",
    ]
    for table in tables:
        conn.execute(f"DELETE FROM {table} WHERE project_id = ?", [project_id])


def _insert_acceptance_criteria(
    conn: "duckdb.DuckDBPyConnection",
    project_id: str,
    task_id: str,
    criteria: list,
) -> None:
    """Insert acceptance criteria for a task or subtask.

    Args:
        conn: DuckDB connection
        project_id: Project ID
        task_id: Task global ID
        criteria: List of AcceptanceCriterion objects
    """
    for i, ac in enumerate(criteria):
        conn.execute(
            """
            INSERT INTO acceptance_criteria
            (project_id, task_id, number, text, completed, sort_order)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            [project_id, task_id, ac.number, ac.text, ac.completed, i],
        )


# =============================================================================
# CLI ENTRY POINT
# =============================================================================


def story_sync_command(
    backlog_dir: Path | None = None,
    output_path: Path | None = None,
    migrate_only: bool = False,
) -> int:
    """Run story sync command.

    Args:
        backlog_dir: Path to Backlog.md directory
        output_path: Output database path
        migrate_only: Only run migrations, don't sync data

    Returns:
        Exit code (0 = success, 1 = error)
    """
    # Import duckdb
    try:
        import duckdb
    except ImportError:
        print("Error: duckdb is required for sync. Install with: pip install rdm[story-audit]")
        return 1

    db_path = (output_path or Path("backlog.duckdb")).resolve()

    # Handle migrate-only
    if migrate_only:
        print(f"Database:     {db_path}")
        print(f"Schema:       v{SCHEMA_VERSION}")
        print("\nRunning migrations only...")

        conn = duckdb.connect(str(db_path))
        ensure_schema_version_table(conn)

        current = get_current_version(conn)
        print(f"Current:      {current or 'none'}")

        applied = run_migrations(conn)
        if applied:
            print(f"Applied:      {', '.join(applied)}")
        else:
            print("No pending migrations.")

        conn.close()
        return 0

    # Validate backlog directory
    if not backlog_dir:
        print("Error: backlog_dir is required")
        return 1

    backlog_dir = backlog_dir.resolve()
    config_path = backlog_dir / "config.yml"

    if not config_path.exists():
        print(f"Error: Not a Backlog.md directory (no config.yml): {backlog_dir}")
        return 1

    # Parse backlog data
    print(f"Backlog:      {backlog_dir}")
    print(f"Schema:       v{SCHEMA_VERSION}")

    print("\nParsing markdown files...")
    try:
        data = extract_backlog_data(backlog_dir)
    except Exception as e:
        print(f"\nError: Failed to parse backlog: {e}")
        return 1

    print(f"  Project:    {data.config.project_name} ({data.project_id})")
    print(f"  Milestones: {len(data.milestones)}")
    print(f"  Tasks:      {len(data.tasks)}")
    print(f"  Subtasks:   {len(data.subtasks)}")
    print(f"  Risks:      {len(data.risks)}")
    print(f"  Decisions:  {len(data.decisions)}")

    # Create/migrate database
    print(f"\nDatabase:     {db_path}")

    conn = duckdb.connect(str(db_path))
    ensure_schema_version_table(conn)

    current = get_current_version(conn)
    if current:
        print(f"Current ver:  {current}")

    applied = run_migrations(conn)
    if applied:
        print(f"Migrations:   {', '.join(applied)}")

    # Populate tables
    print("\nPopulating tables...")
    populate_tables(conn, data)

    conn.close()

    print(f"\nDone! Database saved to: {db_path}")
    return 0


def main() -> None:
    """CLI entry point for standalone usage."""
    import argparse

    parser = argparse.ArgumentParser(description="Sync Backlog.md to DuckDB")
    parser.add_argument("backlog_dir", nargs="?", type=Path, help="Path to Backlog.md directory")
    parser.add_argument("-o", "--output", type=Path, help="Output database path")
    parser.add_argument("--migrate-only", action="store_true", help="Only run migrations")
    args = parser.parse_args()

    sys.exit(
        story_sync_command(
            backlog_dir=args.backlog_dir,
            output_path=args.output,
            migrate_only=args.migrate_only,
        )
    )


if __name__ == "__main__":
    main()
