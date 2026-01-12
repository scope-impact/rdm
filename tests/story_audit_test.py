"""
Tests for story_audit module - Requirements Traceability Tools.

Uses TDD approach following SOLID + KISS principles.
Tests business logic, not framework validation (Pydantic handles that).
"""

from __future__ import annotations

import tempfile
from pathlib import Path



# =============================================================================
# SCHEMA TESTS
# =============================================================================


class TestStoryQualityEnum:
    """Tests for StoryQuality enum values."""

    def test_story_quality_values(self) -> None:
        """StoryQuality has expected enum values."""
        from rdm.story_audit.schema import StoryQuality

        assert StoryQuality.CORE.value == "core"
        assert StoryQuality.ACCEPTABLE.value == "acceptable"
        assert StoryQuality.WEAK.value == "weak"
        assert StoryQuality.UNKNOWN.value == "unknown"


class TestUserStoryModel:
    """Tests for UserStory Pydantic model."""

    def test_user_story_full_story_property(self) -> None:
        """full_story property generates correct format."""
        from rdm.story_audit.schema import UserStory

        story = UserStory(
            id="US-001",
            as_a="developer",
            i_want="to write tests",
            so_that="I can catch bugs early",
        )
        assert story.full_story == "As a developer, I want to write tests so that I can catch bugs early"

    def test_user_story_extra_fields_detected(self) -> None:
        """Extra fields in UserStory are captured."""
        from rdm.story_audit.schema import UserStory

        story = UserStory(
            id="US-001",
            as_a="user",
            i_want="something",
            so_that="benefit",
            custom_field="custom_value",  # Extra field
        )
        extra = story.get_extra_fields()
        assert "custom_field" in extra
        assert extra["custom_field"] == "custom_value"

    def test_user_story_no_extra_fields(self) -> None:
        """No extra fields returns empty dict."""
        from rdm.story_audit.schema import UserStory

        story = UserStory(id="US-001", as_a="user", i_want="x", so_that="y")
        assert story.get_extra_fields() == {}


class TestFeatureModel:
    """Tests for Feature Pydantic model."""

    def test_feature_extra_fields_detected(self) -> None:
        """Extra fields in Feature are captured."""
        from rdm.story_audit.schema import Feature

        feature = Feature(
            id="FT-001",
            title="Test Feature",
            unknown_field="value",  # Extra field
        )
        extra = feature.get_extra_fields()
        assert "unknown_field" in extra

    def test_feature_compute_quality_summary(self) -> None:
        """compute_quality_summary correctly counts story qualities."""
        from rdm.story_audit.schema import Feature, UserStory

        feature = Feature(
            id="FT-001",
            title="Test Feature",
            user_stories=[
                UserStory(id="US-001", as_a="a", i_want="b", so_that="c", story_quality="core"),
                UserStory(id="US-002", as_a="a", i_want="b", so_that="c", story_quality="core"),
                UserStory(id="US-003", as_a="a", i_want="b", so_that="c", story_quality="acceptable"),
                UserStory(id="US-004", as_a="a", i_want="b", so_that="c", story_quality="weak"),
            ],
        )
        summary = feature.compute_quality_summary()
        assert summary.core == 2
        assert summary.acceptable == 1
        assert summary.weak == 1


class TestRiskModels:
    """Tests for Risk and RiskControl models."""

    def test_risk_get_all_implemented_by(self) -> None:
        """get_all_implemented_by collects US IDs from all controls."""
        from rdm.story_audit.schema import Risk, RiskControl

        risk = Risk(
            id="RSK-001",
            title="Test Risk",
            controls=[
                RiskControl(id="RC-001", description="Control 1", implemented_by=["US-001", "US-002"]),
                RiskControl(id="RC-002", description="Control 2", implemented_by=["US-003"]),
            ],
        )
        us_ids = risk.get_all_implemented_by()
        assert us_ids == ["US-001", "US-002", "US-003"]

    def test_risk_control_valid(self) -> None:
        """RiskControl creates with valid data."""
        from rdm.story_audit.schema import RiskControl

        control = RiskControl(
            id="RC-001",
            description="Test control",
            implemented_by=["US-001"],
            verification="Unit tests",
            status="implemented",
        )
        assert control.id == "RC-001"
        assert control.implemented_by == ["US-001"]


class TestRequirementsIndex:
    """Tests for RequirementsIndex model."""

    def test_requirements_index_parse_phases(self) -> None:
        """Phases are correctly parsed from dict."""
        from rdm.story_audit.schema import RequirementsIndex

        index = RequirementsIndex(
            phases={
                "phase_1": {"description": "First phase", "features": ["FT-001"]},
                "phase_2": {"description": "Second phase", "features": ["FT-002"]},
            }
        )
        assert len(index.phases) == 2
        assert index.phases["phase_1"].description == "First phase"
        assert index.phases["phase_1"].features == ["FT-001"]


# =============================================================================
# AUDIT TESTS
# =============================================================================


class TestAuditIdPattern:
    """Tests for ID pattern matching."""

    def test_id_pattern_matches_all_types(self) -> None:
        """ID_PATTERN matches FT, US, EP, DC, GR, ADR prefixes."""
        from rdm.story_audit.audit import ID_PATTERN

        test_cases = [
            ("FT-001", True),
            ("US-123", True),
            ("EP-001", True),
            ("DC-001", True),
            ("GR-001", True),
            ("ADR-001", True),
            ("XX-001", False),
            ("FT001", False),
        ]
        for text, should_match in test_cases:
            match = ID_PATTERN.search(text)
            assert (match is not None) == should_match, f"Failed for {text}"


class TestAuditFindIdsInFile:
    """Tests for find_ids_in_file function."""

    def test_find_ids_in_file(self) -> None:
        """find_ids_in_file extracts IDs with correct metadata."""
        from rdm.story_audit.audit import find_ids_in_file

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write("id: FT-001\nid: US-001\nid: US-002\n")
            f.flush()
            refs = find_ids_in_file(Path(f.name), "requirement")

        assert len(refs) == 3
        assert refs[0].story_id == "FT-001"
        assert refs[0].context == "requirement"
        assert refs[0].line_number == 1


class TestAuditConflictDetection:
    """Tests for conflict detection logic."""

    def test_detect_conflicts_finds_duplicates(self) -> None:
        """detect_conflicts identifies IDs defined in multiple files."""
        from rdm.story_audit.audit import StoryReference, detect_conflicts

        requirements = {
            "FT-001": [
                StoryReference("FT-001", "file1.yaml", 1, "requirement", "id: FT-001"),
                StoryReference("FT-001", "file2.yaml", 1, "requirement", "id: FT-001"),
            ],
            "US-001": [
                StoryReference("US-001", "file1.yaml", 2, "requirement", "id: US-001"),
            ],
        }
        conflicts = detect_conflicts(requirements)
        assert len(conflicts) == 1
        assert conflicts[0][0] == "FT-001"

    def test_detect_conflicts_ignores_references(self) -> None:
        """detect_conflicts only flags definitions, not references."""
        from rdm.story_audit.audit import StoryReference, detect_conflicts

        # FT-001 defined once, referenced elsewhere
        requirements = {
            "FT-001": [
                StoryReference("FT-001", "feature.yaml", 1, "requirement", "id: FT-001"),
                StoryReference("FT-001", "index.yaml", 5, "requirement", "- FT-001"),  # Reference, not definition
            ],
        }
        conflicts = detect_conflicts(requirements)
        assert len(conflicts) == 0  # Should not flag as conflict


class TestAuditResult:
    """Tests for AuditResult dataclass."""

    def test_audit_result_defaults(self) -> None:
        """AuditResult initializes with correct defaults."""
        from rdm.story_audit.audit import AuditResult

        result = AuditResult()
        assert result.all_ids == set()
        assert result.conflicts == []
        assert result.orphan_tests == []


# =============================================================================
# VALIDATE TESTS
# =============================================================================


class TestValidateFeature:
    """Tests for feature validation."""

    def test_validate_feature_valid_file(self) -> None:
        """validate_feature returns valid=True for correct YAML."""
        from rdm.story_audit.validate import validate_feature

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write("""
id: FT-001
title: Test Feature
description: A test feature
business_value: Testing value
user_stories:
  - id: US-001
    as_a: developer
    i_want: to test
    so_that: quality
    acceptance_criteria:
      - Criterion 1
definition_of_done:
  - Item 1
""")
            f.flush()
            result = validate_feature(Path(f.name))

        assert result.valid is True
        assert len(result.errors) == 0
        assert result.stats["user_stories"] == 1

    def test_validate_feature_missing_file(self) -> None:
        """validate_feature returns valid=False for missing file."""
        from rdm.story_audit.validate import validate_feature

        result = validate_feature(Path("/nonexistent/file.yaml"))
        assert result.valid is False
        assert "File not found" in result.errors[0]

    def test_validate_feature_invalid_yaml(self) -> None:
        """validate_feature catches validation errors."""
        from rdm.story_audit.validate import validate_feature

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write("id: INVALID-ID\ntitle: Bad Feature\n")  # Invalid ID format
            f.flush()
            result = validate_feature(Path(f.name))

        assert result.valid is False
        assert len(result.errors) > 0


class TestValidateIndex:
    """Tests for index validation."""

    def test_validate_index_missing_file(self) -> None:
        """validate_index returns valid=False for missing file."""
        from rdm.story_audit.validate import validate_index

        result = validate_index(Path("/nonexistent/dir"))
        assert result.valid is False
        assert "File not found" in result.errors[0]


class TestValidationResult:
    """Tests for ValidationResult dataclass."""

    def test_validation_result_with_warnings(self) -> None:
        """ValidationResult captures warnings correctly."""
        from rdm.story_audit.validate import ValidationResult

        result = ValidationResult(
            file_path=Path("test.yaml"),
            valid=True,
            errors=[],
            warnings=["Missing description"],
            extra_fields={},
            stats={"user_stories": 5},
        )
        assert result.valid is True
        assert len(result.warnings) == 1
        assert result.stats["user_stories"] == 5


# =============================================================================
# CHECK_IDS TESTS
# =============================================================================


class TestCheckIdsFindDefinitions:
    """Tests for find_id_definitions function."""

    def test_find_id_definitions(self) -> None:
        """find_id_definitions extracts ID definitions with line numbers."""
        from rdm.story_audit.check_ids import find_id_definitions

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write("id: FT-001\nother: value\nid: US-001\n")
            f.flush()
            definitions = find_id_definitions(Path(f.name))

        assert len(definitions) == 2
        assert definitions[0] == ("FT-001", 1)
        assert definitions[1] == ("US-001", 3)


class TestCheckForDuplicates:
    """Tests for check_for_duplicates function."""

    def test_check_for_duplicates_finds_conflicts(self) -> None:
        """check_for_duplicates returns only duplicate IDs."""
        from rdm.story_audit.check_ids import check_for_duplicates

        with tempfile.TemporaryDirectory() as tmpdir:
            # Create two files with same ID
            file1 = Path(tmpdir) / "file1.yaml"
            file2 = Path(tmpdir) / "file2.yaml"
            file1.write_text("id: FT-001\n")
            file2.write_text("id: FT-001\n")

            duplicates = check_for_duplicates([file1, file2])

        assert "FT-001" in duplicates
        assert len(duplicates["FT-001"]) == 2

    def test_check_for_duplicates_no_conflicts(self) -> None:
        """check_for_duplicates returns empty for unique IDs."""
        from rdm.story_audit.check_ids import check_for_duplicates

        with tempfile.TemporaryDirectory() as tmpdir:
            file1 = Path(tmpdir) / "file1.yaml"
            file2 = Path(tmpdir) / "file2.yaml"
            file1.write_text("id: FT-001\n")
            file2.write_text("id: FT-002\n")

            duplicates = check_for_duplicates([file1, file2])

        assert len(duplicates) == 0


# =============================================================================
# SYNC TESTS
# =============================================================================


class TestSyncHelpers:
    """Tests for sync helper functions."""

    def test_count_dod_items_list(self) -> None:
        """_count_dod_items counts list items."""
        from rdm.story_audit.sync import _count_dod_items

        assert _count_dod_items(["item1", "item2", "item3"]) == 3

    def test_count_dod_items_dict(self) -> None:
        """_count_dod_items counts dict items across categories."""
        from rdm.story_audit.sync import _count_dod_items

        dod = {
            "testing": ["unit tests", "integration tests"],
            "docs": ["readme", "api docs", "changelog"],
        }
        assert _count_dod_items(dod) == 5


class TestSyncParseYaml:
    """Tests for YAML parsing functions."""

    def test_parse_feature_yaml(self) -> None:
        """parse_feature_yaml returns Feature model."""
        from rdm.story_audit.sync import parse_feature_yaml

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write("""
id: FT-001
title: Test Feature
user_stories: []
definition_of_done: []
""")
            f.flush()
            feature = parse_feature_yaml(Path(f.name))

        assert feature.id == "FT-001"
        assert feature.title == "Test Feature"


class TestSyncBuildFeaturePhaseMap:
    """Tests for build_feature_phase_map function."""

    def test_build_feature_phase_map(self) -> None:
        """build_feature_phase_map creates correct mapping."""
        from rdm.story_audit.schema import RequirementsIndex
        from rdm.story_audit.sync import build_feature_phase_map

        index = RequirementsIndex(
            phases={
                "phase_1": {"description": "Phase 1", "features": ["FT-001", "FT-002"]},
                "phase_2": {"description": "Phase 2", "features": ["FT-003"]},
            }
        )
        phase_map = build_feature_phase_map(index)

        assert phase_map["FT-001"] == "phase_1"
        assert phase_map["FT-002"] == "phase_1"
        assert phase_map["FT-003"] == "phase_2"


# =============================================================================
# INTEGRATION TESTS
# =============================================================================


class TestIntegrationFullWorkflow:
    """Integration tests for full workflow."""

    def test_full_validation_workflow(self) -> None:
        """Full validation workflow with index and feature files."""
        from rdm.story_audit.validate import validate_all

        with tempfile.TemporaryDirectory() as tmpdir:
            yaml_dir = Path(tmpdir)

            # Create _index.yaml
            (yaml_dir / "_index.yaml").write_text("""
project:
  name: Test Project
  description: Test
phases:
  phase_1:
    description: Phase 1
    features:
      - FT-001
epics:
  - id: EP-001
    title: Test Epic
    status: proposed
features:
  - id: FT-001
    title: Test Feature
    status: proposed
""")

            # Create features directory and feature file
            features_dir = yaml_dir / "features"
            features_dir.mkdir()
            (features_dir / "FT-001.yaml").write_text("""
id: FT-001
title: Test Feature
description: Test description
business_value: Test value
user_stories:
  - id: US-001
    as_a: user
    i_want: something
    so_that: benefit
    acceptance_criteria:
      - Criterion 1
definition_of_done:
  - Done item 1
""")

            summary = validate_all(yaml_dir)

        assert summary.total_files == 2
        assert summary.valid_files == 2
        assert summary.invalid_files == 0

    def test_full_audit_workflow(self) -> None:
        """Full audit workflow scans requirements and tests."""
        from rdm.story_audit.audit import run_audit

        with tempfile.TemporaryDirectory() as tmpdir:
            repo_path = Path(tmpdir)

            # Create requirements directory
            req_dir = repo_path / "requirements"
            req_dir.mkdir()
            (req_dir / "FT-001.yaml").write_text("id: FT-001\nid: US-001\n")

            # Create tests directory
            tests_dir = repo_path / "tests"
            tests_dir.mkdir()
            (tests_dir / "test_feature.py").write_text('@allure.story("US-001")\ndef test_something(): pass\n')

            result = run_audit(repo_path)

        assert "FT-001" in result.all_ids
        assert "US-001" in result.all_ids
