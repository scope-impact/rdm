"""Tests for backlog validation."""

import tempfile
from pathlib import Path

import pytest

from rdm.story_audit.backlog_validate import (
    ValidationResult,
    parse_frontmatter,
    validate_backlog,
    validate_config,
    validate_task_file,
    validate_milestone_file,
    validate_decision_file,
    story_backlog_validate_command,
)


class TestParseFrontmatter:
    """Tests for frontmatter parsing."""

    def test_parses_valid_frontmatter(self) -> None:
        content = """---
id: ft-001
title: Test Task
status: Done
---

# Description
Some content here.
"""
        fm, body, end_line = parse_frontmatter(content)
        assert fm is not None
        assert fm["id"] == "ft-001"
        assert fm["title"] == "Test Task"
        assert "# Description" in body

    def test_returns_none_for_missing_frontmatter(self) -> None:
        content = "# Just a heading\n\nSome content."
        fm, body, end_line = parse_frontmatter(content)
        assert fm is None
        assert body == content

    def test_returns_none_for_invalid_yaml(self) -> None:
        content = """---
id: ft-001
title: [invalid yaml
---

Content here.
"""
        fm, body, end_line = parse_frontmatter(content)
        assert fm is None


class TestValidateConfig:
    """Tests for config.yml validation."""

    def test_reports_missing_config(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            config = validate_config(Path(tmpdir) / "config.yml", result)
            assert config is None
            assert len(result.errors) == 1
            assert "E001" in result.errors[0].code

    def test_validates_valid_config(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = Path(tmpdir) / "config.yml"
            config_path.write_text("""
project_name: "Test Project"
task_prefix: "tp"
""")
            config = validate_config(config_path, result)
            assert config is not None
            assert len(result.errors) == 0
            assert config["task_prefix"] == "tp"

    def test_derives_task_prefix_from_project_name(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = Path(tmpdir) / "config.yml"
            config_path.write_text('project_name: "My Test Project"')
            config = validate_config(config_path, result)
            assert config is not None
            assert config["task_prefix"] == "mtp"


class TestValidateTaskFile:
    """Tests for task file validation."""

    def test_validates_valid_task(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            task_path = Path(tmpdir) / "ft-001.md"
            task_path.write_text("""---
id: ft-001
title: Test Task
status: Done
priority: high
---

## Description
Task description here.
""")
            config = {"task_prefix": "ft"}
            task_id = validate_task_file(task_path, result, config, set(), set())
            assert task_id == "ft-001"
            assert len(result.errors) == 0

    def test_reports_missing_frontmatter(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            task_path = Path(tmpdir) / "ft-001.md"
            task_path.write_text("# Just a heading\n\nNo frontmatter here.")
            config = {"task_prefix": "ft"}
            task_id = validate_task_file(task_path, result, config, set(), set())
            assert task_id is None
            assert len(result.errors) == 1
            assert "E010" in result.errors[0].code

    def test_reports_invalid_status(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            task_path = Path(tmpdir) / "ft-001.md"
            task_path.write_text("""---
id: ft-001
title: Test Task
status: InvalidStatus
---
""")
            config = {"task_prefix": "ft"}
            validate_task_file(task_path, result, config, set(), set())
            assert len(result.errors) == 1
            assert "E013" in result.errors[0].code

    def test_warns_on_missing_milestone(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            task_path = Path(tmpdir) / "ft-001.md"
            task_path.write_text("""---
id: ft-001
title: Test Task
status: Done
milestone: m-99
---
""")
            config = {"task_prefix": "ft"}
            validate_task_file(task_path, result, config, {"m-1"}, set())
            assert len(result.warnings) == 1
            assert "W014" in result.warnings[0].code


class TestValidateMilestoneFile:
    """Tests for milestone file validation."""

    def test_validates_valid_milestone(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            milestone_path = Path(tmpdir) / "m-1.md"
            milestone_path.write_text("""---
id: m-1
title: Phase 1
status: active
---

## Description
First milestone.
""")
            milestone_id = validate_milestone_file(milestone_path, result)
            assert milestone_id == "m-1"
            assert len(result.errors) == 0

    def test_reports_invalid_id_format(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            milestone_path = Path(tmpdir) / "milestone-1.md"
            milestone_path.write_text("""---
id: milestone-1
title: Bad ID
---
""")
            validate_milestone_file(milestone_path, result)
            assert len(result.errors) == 1
            assert "E022" in result.errors[0].code


class TestValidateDecisionFile:
    """Tests for decision file validation."""

    def test_validates_valid_decision(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            decision_path = Path(tmpdir) / "decision-1.md"
            decision_path.write_text("""---
id: decision-1
title: ADR-001 Use DuckDB
status: accepted
---

## Context
We need a database.

## Decision
Use DuckDB.
""")
            decision_id = validate_decision_file(decision_path, result)
            assert decision_id == "decision-1"
            assert len(result.errors) == 0

    def test_warns_on_missing_sections(self) -> None:
        result = ValidationResult()
        with tempfile.TemporaryDirectory() as tmpdir:
            decision_path = Path(tmpdir) / "decision-1.md"
            decision_path.write_text("""---
id: decision-1
title: Missing Sections
status: accepted
---

Just some text.
""")
            validate_decision_file(decision_path, result)
            # Should warn about missing Context and Decision sections
            assert len(result.warnings) >= 2


class TestValidateBacklog:
    """Tests for full backlog validation."""

    def test_validates_complete_backlog(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)

            # Create config
            (backlog_dir / "config.yml").write_text("""
project_name: "Test Project"
task_prefix: "tp"
""")

            # Create milestones directory
            milestones_dir = backlog_dir / "milestones"
            milestones_dir.mkdir()
            (milestones_dir / "m-1.md").write_text("""---
id: m-1
title: Phase 1
---
""")

            # Create tasks directory
            tasks_dir = backlog_dir / "tasks"
            tasks_dir.mkdir()
            (tasks_dir / "tp-001.md").write_text("""---
id: tp-001
title: First Task
status: Done
milestone: m-1
---
""")

            result = validate_backlog(backlog_dir)
            assert result.is_valid
            assert result.milestones_count == 1
            assert result.tasks_count == 1

    def test_detects_duplicate_task_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)

            # Create config with required schema fields
            (backlog_dir / "config.yml").write_text("""
project_id: "test"
project_name: "Test"
task_prefix: "t"
""")

            # Create tasks directory with duplicates
            tasks_dir = backlog_dir / "tasks"
            tasks_dir.mkdir()
            (tasks_dir / "task-001.md").write_text("""---
id: t-001
title: Task One
status: Done
---
""")
            (tasks_dir / "task-001-copy.md").write_text("""---
id: t-001
title: Task One Copy
status: Done
---
""")

            result = validate_backlog(backlog_dir)
            assert not result.is_valid
            assert any("E050" in e.code for e in result.errors)

    def test_strict_mode_promotes_warnings_to_errors(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)

            # Create config with required fields for schema
            (backlog_dir / "config.yml").write_text("""
project_id: "test"
project_name: "Test"
task_prefix: "t"
""")

            # Create task with warning-level issue (missing milestone)
            tasks_dir = backlog_dir / "tasks"
            tasks_dir.mkdir()
            (tasks_dir / "t-001.md").write_text("""---
id: t-001
title: Task
status: Done
milestone: m-nonexistent
---
""")

            # Without strict: should pass (only warning)
            result = validate_backlog(backlog_dir, strict=False)
            assert result.is_valid
            assert len(result.warnings) > 0

            # With strict: should fail
            result_strict = validate_backlog(backlog_dir, strict=True)
            assert not result_strict.is_valid


class TestSchemaValidation:
    """Tests for Pydantic schema validation (DuckDB compatibility)."""

    def test_reports_missing_required_config_fields(self) -> None:
        """Config missing required fields fails schema validation."""
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)
            # Missing project_id and task_prefix
            (backlog_dir / "config.yml").write_text('project_name: "Test"')

            result = validate_backlog(backlog_dir)
            assert not result.is_valid
            # Should report schema error for missing fields
            assert any("E002" in e.code for e in result.errors)

    def test_task_schema_validation_matches_sync(self) -> None:
        """Task validation uses same schema as rdm story sync."""
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)
            (backlog_dir / "config.yml").write_text("""
project_id: "test"
project_name: "Test"
task_prefix: "tp"
""")
            tasks_dir = backlog_dir / "tasks"
            tasks_dir.mkdir()

            # Valid task that should sync correctly
            (tasks_dir / "tp-001.md").write_text("""---
id: tp-001
title: Valid Task
status: Done
priority: high
labels:
  - label1
  - label2
---

## Description
This task has proper structure.

## Acceptance Criteria
- [x] #1 First criterion
- [ ] #2 Second criterion
""")

            result = validate_backlog(backlog_dir)
            assert result.is_valid
            assert result.tasks_count == 1

    def test_task_with_defaults_still_validates(self) -> None:
        """Task with missing optional fields gets defaults from parser."""
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)
            (backlog_dir / "config.yml").write_text("""
project_id: "test"
project_name: "Test"
task_prefix: "tp"
""")
            tasks_dir = backlog_dir / "tasks"
            tasks_dir.mkdir()

            # Task with minimal fields - parser provides defaults
            (tasks_dir / "tp-001.md").write_text("""---
id: tp-001
title: Minimal Task
status: Done
---
""")

            result = validate_backlog(backlog_dir)
            # Should pass - parser provides defaults for optional fields
            assert result.is_valid
            assert result.tasks_count == 1

    def test_malformed_yaml_fails_validation(self) -> None:
        """Task with malformed YAML frontmatter fails validation."""
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)
            (backlog_dir / "config.yml").write_text("""
project_id: "test"
project_name: "Test"
task_prefix: "tp"
""")
            tasks_dir = backlog_dir / "tasks"
            tasks_dir.mkdir()

            # Invalid YAML in frontmatter
            (tasks_dir / "tp-001.md").write_text("""---
id: tp-001
title: [broken yaml
status: Done
---
""")

            result = validate_backlog(backlog_dir)
            # Should fail due to parse error
            assert any("E100" in e.code for e in result.errors)


class TestBacklogValidateCommand:
    """Tests for CLI command."""

    def test_returns_0_for_valid_backlog(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)
            (backlog_dir / "config.yml").write_text("""
project_id: "test"
project_name: "Test"
task_prefix: "t"
""")

            exit_code = story_backlog_validate_command(
                backlog_dir=backlog_dir, quiet=True
            )
            assert exit_code == 0

    def test_returns_1_for_invalid_backlog(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            backlog_dir = Path(tmpdir)
            # No config.yml -> should fail

            exit_code = story_backlog_validate_command(
                backlog_dir=backlog_dir, quiet=True
            )
            assert exit_code == 1

    def test_returns_2_for_missing_directory(self) -> None:
        exit_code = story_backlog_validate_command(
            backlog_dir=Path("/nonexistent/path"), quiet=True
        )
        assert exit_code == 2
