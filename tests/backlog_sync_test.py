"""
Tests for Backlog.md sync functionality.

Tests the new Backlog.md-only sync with:
- Pydantic schema for frontmatter parsing
- Markdown body extraction
- DuckDB sync with migrations
"""

from __future__ import annotations

import tempfile
from pathlib import Path

import pytest


# =============================================================================
# SCHEMA TESTS
# =============================================================================


class TestBacklogConfig:
    """Tests for BacklogConfig model."""

    def test_global_prefix_generated_correctly(self) -> None:
        """global_prefix generates {project_id}:{task_prefix} format."""
        from rdm.story_audit.backlog_schema import BacklogConfig

        config = BacklogConfig(
            project_id="hhi",
            task_prefix="ft",
            project_name="Halla Health Infrastructure",
        )
        assert config.global_prefix == "hhi:ft"

    def test_all_optional_fields_have_defaults(self) -> None:
        """Optional fields default to empty/None."""
        from rdm.story_audit.backlog_schema import BacklogConfig

        config = BacklogConfig(
            project_id="test",
            task_prefix="t",
            project_name="Test",
        )
        assert config.description == ""
        assert config.repository is None
        assert config.labels == []


class TestTask:
    """Tests for Task model."""

    def test_is_subtask_true_when_parent_task_id_set(self) -> None:
        """is_subtask returns True when parent_task_id is set."""
        from rdm.story_audit.backlog_schema import Task

        subtask = Task(
            id="ft-003.01",
            title="Subtask",
            status="To Do",
            parent_task_id="ft-003",
        )
        assert subtask.is_subtask is True

        task = Task(
            id="ft-003",
            title="Task",
            status="To Do",
        )
        assert task.is_subtask is False

    def test_acceptance_criteria_counts(self) -> None:
        """acceptance_criteria_count and completed_criteria_count work correctly."""
        from rdm.story_audit.backlog_schema import AcceptanceCriterion, Task

        task = Task(
            id="ft-001",
            title="Test",
            status="In Progress",
            acceptance_criteria=[
                AcceptanceCriterion(number=1, text="AC 1", completed=True),
                AcceptanceCriterion(number=2, text="AC 2", completed=True),
                AcceptanceCriterion(number=3, text="AC 3", completed=False),
            ],
        )
        assert task.acceptance_criteria_count == 3
        assert task.completed_criteria_count == 2


class TestBacklogData:
    """Tests for BacklogData container."""

    def test_make_global_id_prefixes_with_project_id(self) -> None:
        """make_global_id generates {project_id}:{local_id} format."""
        from rdm.story_audit.backlog_schema import BacklogConfig, BacklogData

        data = BacklogData(
            config=BacklogConfig(
                project_id="hhi",
                task_prefix="ft",
                project_name="Test",
            )
        )
        assert data.make_global_id("ft-003") == "hhi:ft-003"
        assert data.make_global_id("m-1") == "hhi:m-1"


# =============================================================================
# PARSER TESTS
# =============================================================================


class TestParseFrontmatter:
    """Tests for YAML frontmatter extraction."""

    def test_extracts_frontmatter_and_body(self) -> None:
        """parse_frontmatter splits YAML header from markdown body."""
        from rdm.story_audit.backlog_parser import parse_frontmatter

        content = """---
id: ft-001
title: "Test Task"
status: Done
---

## Description

This is the body content.
"""
        frontmatter, body = parse_frontmatter(content)

        assert frontmatter["id"] == "ft-001"
        assert frontmatter["title"] == "Test Task"
        assert frontmatter["status"] == "Done"
        assert "## Description" in body
        assert "This is the body content." in body

    def test_handles_no_frontmatter(self) -> None:
        """parse_frontmatter returns empty dict and full content when no frontmatter."""
        from rdm.story_audit.backlog_parser import parse_frontmatter

        content = "# Just a heading\n\nSome content."
        frontmatter, body = parse_frontmatter(content)

        assert frontmatter == {}
        assert body == content


class TestParseAcceptanceCriteria:
    """Tests for AC checkbox parsing."""

    def test_parses_ac_checkboxes(self) -> None:
        """parse_acceptance_criteria extracts checkboxes with number and completion state."""
        from rdm.story_audit.backlog_parser import parse_acceptance_criteria

        body = """## Acceptance Criteria

<!-- AC:BEGIN -->
- [x] #1 First criterion completed
- [ ] #2 Second criterion pending
- [X] #3 Third criterion also completed
<!-- AC:END -->
"""
        criteria = parse_acceptance_criteria(body)

        assert len(criteria) == 3
        assert criteria[0].number == 1
        assert criteria[0].text == "First criterion completed"
        assert criteria[0].completed is True
        assert criteria[1].number == 2
        assert criteria[1].completed is False
        assert criteria[2].completed is True  # [X] also works

    def test_handles_no_ac_markers(self) -> None:
        """parse_acceptance_criteria works without AC:BEGIN/END markers."""
        from rdm.story_audit.backlog_parser import parse_acceptance_criteria

        body = """## Acceptance Criteria

- [x] #1 Criterion one
- [ ] #2 Criterion two
"""
        criteria = parse_acceptance_criteria(body)

        assert len(criteria) == 2


class TestExtractSection:
    """Tests for markdown section extraction."""

    def test_extracts_section_content(self) -> None:
        """extract_section gets content under a heading."""
        from rdm.story_audit.backlog_parser import extract_section

        body = """## Description

This is the description.

## Business Value

This is the business value.

## Notes

Some notes here.
"""
        assert "This is the description." in extract_section(body, "Description")
        assert "This is the business value." in extract_section(body, "Business Value")
        assert "Some notes here." in extract_section(body, "Notes")

    def test_returns_empty_for_missing_section(self) -> None:
        """extract_section returns empty string for missing heading."""
        from rdm.story_audit.backlog_parser import extract_section

        body = """## Description

Content here.
"""
        assert extract_section(body, "Missing Section") == ""


class TestParseConfig:
    """Tests for config.yml parsing."""

    def test_parses_config_file(self) -> None:
        """parse_config extracts project configuration."""
        from rdm.story_audit.backlog_parser import parse_config

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yml", delete=False) as f:
            f.write("""
project_id: "hhi"
task_prefix: "ft"
project_name: "Halla Health Infrastructure"
description: "AWS multi-account infrastructure"
repository: "scope-impact/halla-health-infra"
labels:
  - bootstrap
  - networking
""")
            f.flush()
            config = parse_config(Path(f.name))

        assert config.project_id == "hhi"
        assert config.task_prefix == "ft"
        assert config.project_name == "Halla Health Infrastructure"
        assert "bootstrap" in config.labels

    def test_derives_project_id_from_repository(self) -> None:
        """parse_config derives project_id from repository when not specified."""
        from rdm.story_audit.backlog_parser import parse_config

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yml", delete=False) as f:
            f.write("""
task_prefix: "ft"
project_name: "Test"
repository: "scope-impact/halla-health-infra"
""")
            f.flush()
            config = parse_config(Path(f.name))

        # Should derive "hhi" from "halla-health-infra"
        assert config.project_id == "hhi"


class TestParseTask:
    """Tests for task markdown parsing."""

    def test_parses_task_file(self) -> None:
        """parse_task extracts frontmatter and body content."""
        from rdm.story_audit.backlog_parser import parse_task

        with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as f:
            f.write("""---
id: ft-003
title: "Compute Infrastructure"
status: In Progress
milestone: m-1
priority: high
labels: [kubernetes, alb]
---

## Description

K3s cluster and ALB.

## Business Value

Delivers scalable compute.

## Acceptance Criteria

<!-- AC:BEGIN -->
- [x] #1 K3s operational
- [ ] #2 ALB routing
<!-- AC:END -->
""")
            f.flush()
            task = parse_task(Path(f.name))

        assert task.id == "ft-003"
        assert task.title == "Compute Infrastructure"
        assert task.status == "In Progress"
        assert task.milestone == "m-1"
        assert task.priority == "high"
        assert "kubernetes" in task.labels
        assert "K3s cluster and ALB." in task.description
        assert "Delivers scalable compute." in task.business_value
        assert len(task.acceptance_criteria) == 2
        assert task.acceptance_criteria[0].completed is True


class TestParseRisk:
    """Tests for risk document parsing."""

    def test_parses_risk_table(self) -> None:
        """parse_risk extracts risk table attributes."""
        from rdm.story_audit.backlog_parser import parse_risk

        with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as f:
            f.write("""---
id: doc-risk-iam-001
title: "OIDC Trust Boundary Bypass"
type: risk
labels: [risk, RC-IAM]
---

## Risk Details

| Attribute | Value |
|-----------|-------|
| **STRIDE Category** | Spoofing |
| **Severity** | Critical |
| **Probability** | Unlikely |
| **Risk Level** | High |
| **Cluster** | RC-IAM |

## Hazard

GitHub OIDC provider trusts tokens.

## Situation

Misconfigured OIDC conditions.

## Harm

Infrastructure takeover.

## Affected Requirements

- US-MGMT-002
- US-MGMT-003

## Mitigation

**Status:** Mitigated

### Controls

- OIDC restricted to repo (refs: US-MGMT-003:AC-002)
- Branch restrictions (refs: US-MGMT-003:AC-003)

**Residual Risk:** Low
""")
            f.flush()
            risk = parse_risk(Path(f.name))

        assert risk.id == "doc-risk-iam-001"
        assert risk.stride_category == "Spoofing"
        assert risk.severity == "Critical"
        assert risk.probability == "Unlikely"
        assert risk.risk_level == "High"
        assert "GitHub OIDC provider trusts tokens." in risk.hazard
        assert risk.mitigation_status == "Mitigated"
        assert risk.residual_risk == "Low"
        assert len(risk.controls) == 2
        assert len(risk.affected_requirements) == 2


# =============================================================================
# MIGRATION TESTS
# =============================================================================


class TestMigrationRunner:
    """Tests for database migration framework."""

    def test_ensure_schema_version_table_creates_table(self) -> None:
        """ensure_schema_version_table creates tracking table."""
        duckdb = pytest.importorskip("duckdb")
        from rdm.story_audit.migrations.runner import ensure_schema_version_table

        conn = duckdb.connect(":memory:")
        ensure_schema_version_table(conn)

        tables = conn.execute("SHOW TABLES").fetchall()
        table_names = [t[0] for t in tables]
        assert "schema_version" in table_names

    def test_run_migrations_applies_initial_schema(self) -> None:
        """run_migrations applies 001_initial.py creating all tables."""
        duckdb = pytest.importorskip("duckdb")
        from rdm.story_audit.migrations.runner import (
            ensure_schema_version_table,
            get_current_version,
            run_migrations,
        )

        conn = duckdb.connect(":memory:")
        ensure_schema_version_table(conn)

        applied = run_migrations(conn)
        assert "001" in applied

        # Verify tables created
        tables = conn.execute("SHOW TABLES").fetchall()
        table_names = [t[0] for t in tables]
        assert "projects" in table_names
        assert "tasks" in table_names
        assert "subtasks" in table_names
        assert "milestones" in table_names
        assert "risks" in table_names
        assert "decisions" in table_names

        # Verify version tracked
        assert get_current_version(conn) == "001"

    def test_migrations_idempotent(self) -> None:
        """run_migrations skips already-applied migrations."""
        duckdb = pytest.importorskip("duckdb")
        from rdm.story_audit.migrations.runner import (
            ensure_schema_version_table,
            run_migrations,
        )

        conn = duckdb.connect(":memory:")
        ensure_schema_version_table(conn)

        # First run
        applied1 = run_migrations(conn)
        assert len(applied1) > 0

        # Second run should apply nothing
        applied2 = run_migrations(conn)
        assert len(applied2) == 0


# =============================================================================
# SYNC TESTS
# =============================================================================


class TestPopulateTables:
    """Tests for DuckDB table population."""

    def test_populates_all_tables(self) -> None:
        """populate_tables inserts data into all tables correctly."""
        duckdb = pytest.importorskip("duckdb")
        from rdm.story_audit.backlog_schema import (
            AcceptanceCriterion,
            BacklogConfig,
            BacklogData,
            Decision,
            Milestone,
            RiskDoc,
            Task,
        )
        from rdm.story_audit.migrations.runner import (
            ensure_schema_version_table,
            run_migrations,
        )
        from rdm.story_audit.sync import populate_tables

        conn = duckdb.connect(":memory:")
        ensure_schema_version_table(conn)
        run_migrations(conn)

        data = BacklogData(
            config=BacklogConfig(
                project_id="test",
                task_prefix="ft",
                project_name="Test Project",
            ),
            milestones=[
                Milestone(id="m-1", title="Milestone 1", status="active"),
            ],
            tasks=[
                Task(
                    id="ft-001",
                    title="Task 1",
                    status="Done",
                    milestone="m-1",
                    acceptance_criteria=[
                        AcceptanceCriterion(number=1, text="AC 1", completed=True),
                    ],
                ),
            ],
            subtasks=[
                Task(
                    id="ft-001.01",
                    title="Subtask 1",
                    status="Done",
                    parent_task_id="ft-001",
                ),
            ],
            risks=[
                RiskDoc(
                    id="doc-risk-test-001",
                    title="Test Risk",
                    stride_category="Spoofing",
                    severity="High",
                ),
            ],
            decisions=[
                Decision(
                    id="decision-1",
                    title="Test Decision",
                    status="accepted",
                ),
            ],
        )

        populate_tables(conn, data)

        # Verify counts
        assert conn.execute("SELECT COUNT(*) FROM projects").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM milestones").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM subtasks").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM risks").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM decisions").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM acceptance_criteria").fetchone()[0] == 1

        # Verify global IDs
        task_row = conn.execute("SELECT global_id, local_id FROM tasks").fetchone()
        assert task_row[0] == "test:ft-001"
        assert task_row[1] == "ft-001"


class TestStorySyncCommand:
    """Tests for CLI command."""

    def test_sync_creates_database_from_backlog(self) -> None:
        """story_sync_command creates DuckDB from Backlog.md directory."""
        duckdb = pytest.importorskip("duckdb")
        from rdm.story_audit.sync import story_sync_command

        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)

            # Create config
            (backlog_dir / "config.yml").write_text("""
project_id: "test"
task_prefix: "ft"
project_name: "Test Project"
""")

            # Create milestones
            (backlog_dir / "milestones").mkdir()
            (backlog_dir / "milestones" / "m-1 - Test.md").write_text("""---
id: m-1
title: "Test Milestone"
status: active
---

## Description

Test milestone.
""")

            # Create tasks
            (backlog_dir / "tasks").mkdir()
            (backlog_dir / "tasks" / "ft-001 - Test.md").write_text("""---
id: ft-001
title: "Test Task"
status: Done
milestone: m-1
---

## Description

Test task.

## Acceptance Criteria

<!-- AC:BEGIN -->
- [x] #1 Done
<!-- AC:END -->
""")

            # Create decisions
            (backlog_dir / "decisions").mkdir()
            (backlog_dir / "decisions" / "decision-1 - Test.md").write_text("""---
id: decision-1
title: "Test Decision"
status: accepted
---

## Context

Test context.

## Decision

Test decision.
""")

            db_path = Path(tmpdir) / "test.duckdb"
            result = story_sync_command(
                backlog_dir=backlog_dir,
                output_path=db_path,
            )

            assert result == 0
            assert db_path.exists()

            # Verify contents
            conn = duckdb.connect(str(db_path))
            assert conn.execute("SELECT COUNT(*) FROM projects").fetchone()[0] == 1
            assert conn.execute("SELECT COUNT(*) FROM milestones").fetchone()[0] == 1
            assert conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0] == 1
            assert conn.execute("SELECT COUNT(*) FROM decisions").fetchone()[0] == 1

            # Verify global_id format
            proj = conn.execute("SELECT project_id, project_name FROM projects").fetchone()
            assert proj[0] == "test"
            assert proj[1] == "Test Project"

            conn.close()

    def test_migrate_only_runs_migrations_without_data(self) -> None:
        """--migrate-only creates tables without requiring backlog data."""
        duckdb = pytest.importorskip("duckdb")
        from rdm.story_audit.sync import story_sync_command

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "test.duckdb"

            result = story_sync_command(
                output_path=db_path,
                migrate_only=True,
            )

            assert result == 0
            assert db_path.exists()

            conn = duckdb.connect(str(db_path))
            tables = conn.execute("SHOW TABLES").fetchall()
            table_names = [t[0] for t in tables]

            assert "projects" in table_names
            assert "tasks" in table_names
            assert conn.execute("SELECT COUNT(*) FROM projects").fetchone()[0] == 0

            conn.close()


# =============================================================================
# INTEGRATION TESTS
# =============================================================================


class TestHallaHealthInfraBacklog:
    """Integration tests using real halla-health-infra backlog."""

    @pytest.fixture
    def hhi_backlog_dir(self) -> Path | None:
        """Get halla-health-infra backlog directory if available."""
        path = Path("/Users/sathish.narayanan/Documents/git/Backlog.md/backlog/halla-health-infra")
        if path.exists():
            return path
        return None

    def test_parses_hhi_backlog(self, hhi_backlog_dir: Path | None) -> None:
        """Parses real halla-health-infra backlog without errors."""
        if hhi_backlog_dir is None:
            pytest.skip("halla-health-infra backlog not available")

        from rdm.story_audit.backlog_parser import extract_backlog_data

        data = extract_backlog_data(hhi_backlog_dir)

        # Verify expected counts (adjust as backlog grows)
        assert len(data.milestones) >= 2
        assert len(data.tasks) >= 5
        assert len(data.subtasks) >= 20
        assert len(data.risks) >= 10
        assert len(data.decisions) >= 5

        # Verify project ID derived from repo
        assert data.project_id in ["hhi", "halla-health-infra"]

    def test_syncs_hhi_backlog_to_duckdb(self, hhi_backlog_dir: Path | None) -> None:
        """Syncs real halla-health-infra backlog to DuckDB."""
        if hhi_backlog_dir is None:
            pytest.skip("halla-health-infra backlog not available")

        duckdb = pytest.importorskip("duckdb")
        from rdm.story_audit.sync import story_sync_command

        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "hhi.duckdb"

            result = story_sync_command(
                backlog_dir=hhi_backlog_dir,
                output_path=db_path,
            )

            assert result == 0

            conn = duckdb.connect(str(db_path))

            # Verify global IDs have project prefix
            task_row = conn.execute(
                "SELECT global_id, local_id FROM tasks LIMIT 1"
            ).fetchone()
            assert ":" in task_row[0]  # Global ID has prefix
            assert ":" not in task_row[1]  # Local ID is original

            conn.close()
