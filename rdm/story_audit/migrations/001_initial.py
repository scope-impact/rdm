"""
Initial schema for Backlog.md format.

Schema Version: 2.0.0
Creates all tables for Backlog.md sync with project_id prefix for cross-project support.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import duckdb


def up(conn: "duckdb.DuckDBPyConnection") -> None:
    """Create initial tables for Backlog.md schema."""

    # Schema metadata (created first by runner, but ensure it exists)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS schema_version (
            version VARCHAR PRIMARY KEY,
            applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)

    # Projects (from config.yml)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS projects (
            project_id VARCHAR PRIMARY KEY,
            task_prefix VARCHAR NOT NULL,
            project_name VARCHAR NOT NULL,
            description VARCHAR,
            repository VARCHAR,
            synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)

    # Milestones (formerly epics)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS milestones (
            global_id VARCHAR PRIMARY KEY,
            project_id VARCHAR NOT NULL,
            local_id VARCHAR NOT NULL,
            title VARCHAR NOT NULL,
            description VARCHAR,
            status VARCHAR DEFAULT 'active',
            task_count INTEGER DEFAULT 0,
            source_file VARCHAR
        )
    """)

    # Tasks (parent tasks = features)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS tasks (
            global_id VARCHAR PRIMARY KEY,
            project_id VARCHAR NOT NULL,
            local_id VARCHAR NOT NULL,
            title VARCHAR NOT NULL,
            description VARCHAR,
            business_value VARCHAR,
            status VARCHAR NOT NULL,
            milestone_id VARCHAR,
            priority VARCHAR DEFAULT 'medium',
            labels VARCHAR[],
            created_date DATE,
            subtask_count INTEGER DEFAULT 0,
            acceptance_criteria_count INTEGER DEFAULT 0,
            completed_criteria_count INTEGER DEFAULT 0,
            source_file VARCHAR
        )
    """)

    # Subtasks (user stories)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS subtasks (
            global_id VARCHAR PRIMARY KEY,
            project_id VARCHAR NOT NULL,
            local_id VARCHAR NOT NULL,
            parent_task_id VARCHAR NOT NULL,
            title VARCHAR NOT NULL,
            description VARCHAR,
            status VARCHAR NOT NULL,
            labels VARCHAR[],
            created_date DATE,
            acceptance_criteria_count INTEGER DEFAULT 0,
            completed_criteria_count INTEGER DEFAULT 0,
            source_file VARCHAR
        )
    """)

    # Acceptance criteria (from both tasks and subtasks)
    # Using autoincrement sequence for id
    conn.execute("""
        CREATE SEQUENCE IF NOT EXISTS acceptance_criteria_seq
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS acceptance_criteria (
            id INTEGER PRIMARY KEY DEFAULT nextval('acceptance_criteria_seq'),
            project_id VARCHAR NOT NULL,
            task_id VARCHAR NOT NULL,
            number INTEGER NOT NULL,
            text VARCHAR NOT NULL,
            completed BOOLEAN DEFAULT FALSE,
            sort_order INTEGER
        )
    """)

    # Risk documents
    conn.execute("""
        CREATE TABLE IF NOT EXISTS risks (
            global_id VARCHAR PRIMARY KEY,
            project_id VARCHAR NOT NULL,
            local_id VARCHAR NOT NULL,
            title VARCHAR NOT NULL,
            stride_category VARCHAR,
            severity VARCHAR,
            probability VARCHAR,
            risk_level VARCHAR,
            cluster VARCHAR,
            hazard VARCHAR,
            situation VARCHAR,
            harm VARCHAR,
            description VARCHAR,
            mitigation_status VARCHAR,
            residual_risk VARCHAR,
            labels VARCHAR[],
            control_count INTEGER DEFAULT 0,
            source_file VARCHAR
        )
    """)

    # Risk affected requirements
    conn.execute("""
        CREATE SEQUENCE IF NOT EXISTS risk_requirements_seq
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS risk_requirements (
            id INTEGER PRIMARY KEY DEFAULT nextval('risk_requirements_seq'),
            project_id VARCHAR NOT NULL,
            risk_id VARCHAR NOT NULL,
            requirement_id VARCHAR NOT NULL
        )
    """)

    # Risk controls
    conn.execute("""
        CREATE SEQUENCE IF NOT EXISTS risk_controls_seq
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS risk_controls (
            id INTEGER PRIMARY KEY DEFAULT nextval('risk_controls_seq'),
            project_id VARCHAR NOT NULL,
            risk_id VARCHAR NOT NULL,
            description VARCHAR NOT NULL,
            refs VARCHAR[],
            sort_order INTEGER
        )
    """)

    # Decisions (ADRs)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS decisions (
            global_id VARCHAR PRIMARY KEY,
            project_id VARCHAR NOT NULL,
            local_id VARCHAR NOT NULL,
            title VARCHAR NOT NULL,
            date DATE,
            status VARCHAR DEFAULT 'accepted',
            context VARCHAR,
            decision VARCHAR,
            rationale VARCHAR,
            consequences VARCHAR,
            labels VARCHAR[],
            source_file VARCHAR
        )
    """)

    # Labels dimension (deduplicated)
    conn.execute("""
        CREATE SEQUENCE IF NOT EXISTS labels_seq
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS labels (
            id INTEGER PRIMARY KEY DEFAULT nextval('labels_seq'),
            project_id VARCHAR NOT NULL,
            name VARCHAR NOT NULL,
            UNIQUE(project_id, name)
        )
    """)


def down(conn: "duckdb.DuckDBPyConnection") -> None:
    """Drop all tables (for testing/development)."""
    tables = [
        "labels",
        "decisions",
        "risk_controls",
        "risk_requirements",
        "risks",
        "acceptance_criteria",
        "subtasks",
        "tasks",
        "milestones",
        "projects",
        "schema_version",
    ]
    for table in tables:
        conn.execute(f"DROP TABLE IF EXISTS {table}")
