"""Acceptance tests for the story-audit context's design inputs (see dhf/).

Each test is the acceptance criterion ("live BDD") for a traceability-integrity
design input, tagged `@allure.story("DI-…")`, exercising the real story-audit
engine.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.story_audit.audit import StoryReference, detect_conflicts
from rdm.story_audit.check_ids import check_for_duplicates, find_id_definitions

allure = pytest.importorskip("allure")


@allure.story("DI-13")
@allure.label("output", "rdm/story_audit/check_ids.py")
def test_detects_duplicate_ids_across_files(tmp_path: Path) -> None:
    """DI-13: an ID defined in two files is a conflict; a unique ID is not."""
    f1 = tmp_path / "a.yaml"
    f2 = tmp_path / "b.yaml"
    f1.write_text("id: FT-001\n")
    f2.write_text("id: FT-001\nid: US-009\n")

    duplicates = check_for_duplicates([f1, f2])
    assert "FT-001" in duplicates and len(duplicates["FT-001"]) == 2  # defined twice
    assert "US-009" not in duplicates  # defined once → not a conflict


@allure.story("DI-14")
@allure.label("output", "rdm/story_audit/audit.py")
def test_locates_definitions_and_flags_only_definitions(tmp_path: Path) -> None:
    """DI-14: definitions are located with line numbers; only definitions (not
    references) are flagged as conflicts."""
    f = tmp_path / "x.yaml"
    f.write_text("id: FT-001\nother: value\nid: US-001\n")
    assert find_id_definitions(f) == [("FT-001", 1), ("US-001", 3)]

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
    assert len(conflicts) == 1 and conflicts[0][0] == "FT-001"  # only the duplicated one
