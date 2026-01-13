"""
Tests for story_audit module - Requirements Traceability Tools.

Uses TDD approach following SOLID + KISS principles.
Tests business logic, not framework validation (Pydantic handles that).
"""

from __future__ import annotations

import tempfile
from pathlib import Path


# =============================================================================
# SCHEMA TESTS - Business Logic Only
# =============================================================================


class TestUserStoryModel:
    """Tests for UserStory computed properties and extra field detection."""

    def test_full_story_property_generates_correct_format(self) -> None:
        """full_story property generates 'As a X, I want Y so that Z' format."""
        from rdm.story_audit.schema import UserStory

        story = UserStory(
            id="US-001",
            as_a="developer",
            i_want="to write tests",
            so_that="I can catch bugs early",
        )
        assert story.full_story == "As a developer, I want to write tests so that I can catch bugs early"

    def test_extra_fields_detected(self) -> None:
        """Extra fields in UserStory are captured for schema migration tracking."""
        from rdm.story_audit.schema import UserStory

        story = UserStory(
            id="US-001",
            as_a="user",
            i_want="something",
            so_that="benefit",
            custom_field="custom_value",
        )
        extra = story.get_extra_fields()
        assert "custom_field" in extra
        assert extra["custom_field"] == "custom_value"


class TestFeatureModel:
    """Tests for Feature computed properties."""

    def test_compute_quality_summary_counts_story_qualities(self) -> None:
        """compute_quality_summary correctly counts core/acceptable/weak stories."""
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
    """Tests for Risk traceability linking."""

    def test_get_all_story_refs_collects_user_story_ids(self) -> None:
        """get_all_story_refs aggregates US IDs from all controls via ac_refs."""
        from rdm.story_audit.schema import Risk, RiskControl, RiskMitigation

        risk = Risk(
            id="RISK-IAM-001",
            title="Test Risk",
            stride="spoofing",
            hazard="Test hazard",
            situation="Test situation",
            harm="Test harm",
            severity="critical",
            probability="unlikely",
            level="high",
            mitigation=RiskMitigation(
                status="mitigated",
                controls=[
                    RiskControl(control="Control 1", ac_refs=["US-001:AC-001", "US-002:AC-001"]),
                    RiskControl(control="Control 2", ac_refs=["US-003:AC-001"]),
                ],
                residual_risk="low",
            ),
        )
        # get_all_story_refs extracts US IDs from ac_refs
        story_refs = risk.get_all_story_refs()
        assert "US-001" in story_refs
        assert "US-002" in story_refs
        assert "US-003" in story_refs

    def test_risk_control_extracts_story_refs_from_ac_refs(self) -> None:
        """RiskControl.story_refs extracts US-XXX from US-XXX:AC-XXX format."""
        from rdm.story_audit.schema import RiskControl

        control = RiskControl(
            control="OIDC restricted to repo",
            ac_refs=["US-MGMT-003:AC-002", "US-MGMT-003:AC-003"],
        )
        assert control.story_refs == ["US-MGMT-003"]

    def test_risk_cluster_parses_correctly(self) -> None:
        """RiskCluster parses metadata, risks, and affected_requirements."""
        from rdm.story_audit.schema import RiskCluster, RiskClusterMetadata

        cluster = RiskCluster(
            metadata=RiskClusterMetadata(
                cluster_id="RC-IAM",
                cluster_name="Identity & Access Management",
                description="IAM risks",
                stride_categories=["spoofing", "elevation"],
            ),
            affected_requirements=["US-MGMT-001", "US-MGMT-002"],
            risks=[],
        )
        assert cluster.cluster_id == "RC-IAM"
        assert cluster.cluster_name == "Identity & Access Management"
        assert len(cluster.affected_requirements) == 2


class TestRequirementsIndex:
    """Tests for RequirementsIndex nested parsing."""

    def test_phases_parsed_from_nested_dict(self) -> None:
        """Phases dict is correctly parsed into Phase objects."""
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
# AUDIT TESTS - Core Scanning Logic
# =============================================================================


class TestIdPatterns:
    """Tests for ID pattern regex matching - single source of truth in schema.py."""

    def test_id_pattern_matches_all_valid_prefixes(self) -> None:
        """ID_PATTERN matches standard ID formats with digits at the end."""
        from rdm.story_audit.schema import ID_PATTERN

        valid = [
            "FT-001", "FT-1", "FT-12345",  # Feature - any digit count
            "US-001", "US-1", "US-999",     # User Story
            "EP-001", "EP-42",              # Epic
            "RISK-IAM-001", "RISK-DATA-1",  # Risk (new format: RISK-CLUSTER-NNN)
            "DC-001", "DC-1",               # Design Control
            "GR-001", "GR-1",               # Guidance Reference
            "ADR-001", "ADR-1",             # Architecture Decision Record
            "RC-001", "RC-42",              # RC with digits (legacy control IDs)
        ]
        # Note: RC-IAM (cluster IDs) don't match the general ID_PATTERN
        # They use RISK_CLUSTER_ID_PATTERN separately
        invalid = ["XX-001", "FT001", "ft-001", "US-", "RSK-001", "-001", "RC-IAM"]

        for text in valid:
            assert ID_PATTERN.search(text) is not None, f"Should match: {text}"
        for text in invalid:
            assert ID_PATTERN.search(text) is None, f"Should not match: {text}"

    def test_risk_id_pattern_validates_correctly(self) -> None:
        """RISK_ID_PATTERN validates RISK-CLUSTER-NNN format."""
        import re

        from rdm.story_audit.schema import RISK_CLUSTER_ID_PATTERN, RISK_ID_PATTERN

        # Risk IDs
        assert re.match(RISK_ID_PATTERN, "RISK-IAM-001") is not None
        assert re.match(RISK_ID_PATTERN, "RISK-DATA-123") is not None
        assert re.match(RISK_ID_PATTERN, "RSK-001") is None  # Old format not supported

        # Cluster IDs
        assert re.match(RISK_CLUSTER_ID_PATTERN, "RC-IAM") is not None
        assert re.match(RISK_CLUSTER_ID_PATTERN, "RC-DATA") is not None
        assert re.match(RISK_CLUSTER_ID_PATTERN, "RC-001") is None  # Must be letters

    def test_id_definition_pattern_matches_yaml_definitions(self) -> None:
        """ID_DEFINITION_PATTERN matches 'id: XX-NNN' format in YAML."""
        from rdm.story_audit.schema import ID_DEFINITION_PATTERN

        valid_lines = [
            "id: FT-001",
            "id: US-123",
            "id: RC-42",
            "  id: EP-1",  # indented
        ]
        invalid_lines = [
            "epic_id: EP-001",  # Not a definition (different key)
            "feature_id: FT-001",  # Not a definition (different key)
            "idx: FT-001",  # Not 'id:' key
        ]
        # Note: RISK-IAM-001 format uses different validation (RISK_ID_PATTERN)

        for line in valid_lines:
            assert ID_DEFINITION_PATTERN.search(line) is not None, f"Should match: {line}"
        for line in invalid_lines:
            assert ID_DEFINITION_PATTERN.search(line) is None, f"Should not match: {line}"

    def test_helper_functions(self) -> None:
        """Test is_valid_id, get_id_prefix, get_id_type helper functions."""
        from rdm.story_audit.schema import get_id_prefix, get_id_type, is_valid_id

        assert is_valid_id("FT-001") is True
        assert is_valid_id("RISK-IAM-001") is True
        assert is_valid_id("INVALID") is False

        assert get_id_prefix("FT-001") == "FT"
        assert get_id_prefix("RISK-IAM-001") == "RISK"
        assert get_id_prefix("INVALID") is None

        assert get_id_type("FT-001") == "Feature"
        assert get_id_type("RISK-IAM-001") == "Risk"
        assert get_id_type("RC-001") == "Risk Cluster"  # RC with digits still valid


class TestAuditFindIdsInFile:
    """Tests for find_ids_in_file function."""

    def test_extracts_ids_with_correct_metadata(self) -> None:
        """find_ids_in_file extracts story IDs with file path, line number, context."""
        from rdm.story_audit.audit import find_ids_in_file

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write("id: FT-001\nid: US-001\nid: US-002\n")
            f.flush()
            refs = find_ids_in_file(Path(f.name), "requirement")

        assert len(refs) == 3
        assert refs[0].story_id == "FT-001"
        assert refs[0].context == "requirement"
        assert refs[0].line_number == 1

    def test_logs_warning_on_file_error(self, capsys: object) -> None:
        """find_ids_in_file logs warning to stderr when file cannot be read."""
        from rdm.story_audit.audit import find_ids_in_file

        refs = find_ids_in_file(Path("/nonexistent/path/file.yaml"), "requirement")
        captured = capsys.readouterr()

        assert refs == []
        assert "Warning" in captured.err


class TestAuditConflictDetection:
    """Tests for conflict detection logic."""

    def test_detects_duplicate_definitions_across_files(self) -> None:
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

    def test_ignores_references_only_flags_definitions(self) -> None:
        """detect_conflicts only flags 'id: XX-XXX' definitions, not references."""
        from rdm.story_audit.audit import StoryReference, detect_conflicts

        requirements = {
            "FT-001": [
                StoryReference("FT-001", "feature.yaml", 1, "requirement", "id: FT-001"),
                StoryReference("FT-001", "index.yaml", 5, "requirement", "- FT-001"),
            ],
        }
        conflicts = detect_conflicts(requirements)
        assert len(conflicts) == 0

    def test_no_false_positive_when_id_defines_different_value(self) -> None:
        """detect_conflicts uses regex to match exact ID after 'id:' key."""
        from rdm.story_audit.audit import StoryReference, detect_conflicts

        requirements = {
            "FT-001": [
                StoryReference("FT-001", "file1.yaml", 1, "requirement", "id: FT-001"),
                StoryReference("FT-001", "file2.yaml", 5, "requirement", "id: FT-002  # refs FT-001"),
            ],
        }
        conflicts = detect_conflicts(requirements)
        assert len(conflicts) == 0

    def test_ignores_epic_id_and_feature_id_references(self) -> None:
        """detect_conflicts ignores epic_id: and feature_id: which are references."""
        from rdm.story_audit.audit import StoryReference, detect_conflicts

        requirements = {
            "EP-001": [
                StoryReference("EP-001", "epic.yaml", 1, "requirement", "id: EP-001"),
                StoryReference("EP-001", "feature.yaml", 5, "requirement", "epic_id: EP-001"),
            ],
        }
        conflicts = detect_conflicts(requirements)
        assert len(conflicts) == 0


# =============================================================================
# VALIDATE TESTS
# =============================================================================


class TestValidateFeature:
    """Tests for feature validation."""

    def test_valid_file_returns_valid_true(self) -> None:
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

    def test_missing_file_returns_valid_false(self) -> None:
        """validate_feature returns valid=False with error for missing file."""
        from rdm.story_audit.validate import validate_feature

        result = validate_feature(Path("/nonexistent/file.yaml"))
        assert result.valid is False
        assert "File not found" in result.errors[0]

    def test_invalid_yaml_returns_validation_errors(self) -> None:
        """validate_feature catches schema validation errors."""
        from rdm.story_audit.validate import validate_feature

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write("id: INVALID-ID\ntitle: Bad Feature\n")
            f.flush()
            result = validate_feature(Path(f.name))

        assert result.valid is False
        assert len(result.errors) > 0


# =============================================================================
# CHECK_IDS TESTS
# =============================================================================


class TestCheckIds:
    """Tests for duplicate ID checking using sync."""

    def test_find_duplicates_detects_duplicate_ids(self) -> None:
        """find_duplicates detects duplicate IDs across entity types."""
        from rdm.story_audit.check_ids import _find_duplicates_python

        data = {
            "features": [
                {"feature_id": "FT-001", "source_file": "FT-001.yaml"},
                {"feature_id": "FT-002", "source_file": "FT-002.yaml"},
            ],
            "user_stories": [
                {"story_id": "US-001", "feature_id": "FT-001"},
                {"story_id": "US-001", "feature_id": "FT-002"},  # duplicate
            ],
            "epics": [],
            "risks": [],
            "risk_controls": [],
        }

        duplicates = _find_duplicates_python(data)

        assert "US-001" in duplicates
        assert len(duplicates["US-001"]) == 2

    def test_find_duplicates_returns_empty_when_no_duplicates(self) -> None:
        """find_duplicates returns empty dict when no duplicates exist."""
        from rdm.story_audit.check_ids import _find_duplicates_python

        data = {
            "features": [{"feature_id": "FT-001", "source_file": "FT-001.yaml"}],
            "user_stories": [{"story_id": "US-001", "feature_id": "FT-001"}],
            "epics": [{"epic_id": "EP-001"}],
            "risks": [],
            "risk_controls": [],
        }

        duplicates = _find_duplicates_python(data)

        assert duplicates == {}


# =============================================================================
# SYNC TESTS
# =============================================================================


class TestSyncHelpers:
    """Tests for sync helper functions."""

    def test_count_dod_items_handles_list_and_dict(self) -> None:
        """_count_dod_items counts items in both list and categorized dict formats."""
        from rdm.story_audit.sync import _count_dod_items

        assert _count_dod_items(["item1", "item2", "item3"]) == 3
        assert _count_dod_items({
            "testing": ["unit tests", "integration tests"],
            "docs": ["readme", "api docs", "changelog"],
        }) == 5


class TestSyncBuildFeaturePhaseMap:
    """Tests for feature-to-phase mapping."""

    def test_creates_correct_feature_to_phase_mapping(self) -> None:
        """build_feature_phase_map creates feature_id -> phase_id mapping."""
        from rdm.story_audit.schema import RequirementsIndex
        from rdm.story_audit.sync import build_feature_phase_map

        index = RequirementsIndex(
            phases={
                "phase_1": {"description": "Phase 1", "features": ["FT-001", "FT-002"]},
                "phase_2": {"description": "Phase 2", "features": ["FT-003"]},
            }
        )
        phase_map = build_feature_phase_map(index)

        assert phase_map == {"FT-001": "phase_1", "FT-002": "phase_1", "FT-003": "phase_2"}


class TestSyncCreatesAllTables:
    """Tests for complete DuckDB sync functionality."""

    def test_sync_creates_all_required_tables_with_data(self) -> None:
        """story_sync_command creates phases, epics, features, user_stories, acceptance_criteria tables."""
        pytest = __import__("pytest")
        duckdb = pytest.importorskip("duckdb")

        from rdm.story_audit.sync import story_sync_command

        with tempfile.TemporaryDirectory() as tmpdir:
            yaml_dir = Path(tmpdir)

            (yaml_dir / "_index.yaml").write_text("""
project:
  name: Test
  description: Test
phases:
  phase_1:
    description: Phase 1
    features: [FT-001]
  phase_2:
    description: Phase 2
    features: []
epics:
  - id: EP-001
    title: Test Epic
    status: proposed
""")

            features_dir = yaml_dir / "features"
            features_dir.mkdir()
            (features_dir / "FT-001.yaml").write_text("""
id: FT-001
title: Test Feature
user_stories:
  - id: US-001
    as_a: user
    i_want: something
    so_that: benefit
    acceptance_criteria:
      - First criterion
      - Second criterion
definition_of_done: [DOD1]
""")

            db_path = Path(tmpdir) / "test.duckdb"
            result = story_sync_command(
                requirements_dir=yaml_dir,
                output_path=db_path,
            )

            assert result == 0

            conn = duckdb.connect(str(db_path))
            tables = conn.execute("SHOW TABLES").fetchall()
            table_names = [t[0] for t in tables]

            # Verify all key tables exist
            assert "phases" in table_names
            assert "epics" in table_names
            assert "features" in table_names
            assert "user_stories" in table_names
            assert "acceptance_criteria" in table_names

            # Verify data counts
            assert conn.execute("SELECT COUNT(*) FROM phases").fetchone()[0] == 2
            assert conn.execute("SELECT COUNT(*) FROM epics").fetchone()[0] == 1
            assert conn.execute("SELECT COUNT(*) FROM features").fetchone()[0] == 1
            assert conn.execute("SELECT COUNT(*) FROM user_stories").fetchone()[0] == 1
            assert conn.execute("SELECT COUNT(*) FROM acceptance_criteria").fetchone()[0] == 2

            conn.close()


# =============================================================================
# INTEGRATION TESTS
# =============================================================================


class TestIntegrationWorkflows:
    """Integration tests for full workflows."""

    def test_full_validation_workflow(self) -> None:
        """validate_all processes index and feature files correctly."""
        from rdm.story_audit.validate import validate_all

        with tempfile.TemporaryDirectory() as tmpdir:
            yaml_dir = Path(tmpdir)

            (yaml_dir / "_index.yaml").write_text("""
project:
  name: Test Project
  description: Test
phases:
  phase_1:
    description: Phase 1
    features: [FT-001]
epics:
  - id: EP-001
    title: Test Epic
    status: proposed
features:
  - id: FT-001
    title: Test Feature
    status: proposed
""")

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
    acceptance_criteria: [Criterion 1]
definition_of_done: [Done item 1]
""")

            summary = validate_all(yaml_dir)

        assert summary.total_files == 2
        assert summary.valid_files == 2
        assert summary.invalid_files == 0

    def test_full_audit_workflow(self) -> None:
        """run_audit scans requirements and tests directories for story IDs."""
        from rdm.story_audit.audit import run_audit

        with tempfile.TemporaryDirectory() as tmpdir:
            repo_path = Path(tmpdir)

            req_dir = repo_path / "requirements"
            req_dir.mkdir()
            (req_dir / "FT-001.yaml").write_text("id: FT-001\nid: US-001\n")

            tests_dir = repo_path / "tests"
            tests_dir.mkdir()
            (tests_dir / "test_feature.py").write_text('@allure.story("US-001")\ndef test_something(): pass\n')

            result = run_audit(repo_path)

        assert "FT-001" in result.all_ids
        assert "US-001" in result.all_ids
