"""Acceptance tests for the story-audit context's design inputs (see dhf/).

Each test is the acceptance criterion ("live BDD") for a traceability-integrity
design input, tagged with `@allure.story` and its DI id, exercising the real
story-audit engine.

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

    # An ID defined once but merely *referenced* elsewhere ("- FT-002") is NOT a
    # conflict — references must not be mistaken for definitions.
    referenced = {
        "FT-002": [
            StoryReference("FT-002", "feature.yaml", 1, "requirement", "id: FT-002"),
            StoryReference("FT-002", "index.yaml", 5, "requirement", "- FT-002"),
        ],
    }
    assert detect_conflicts(referenced) == []


def _record_first_repo(tmp_path: Path) -> Path:
    """A repo with a DHF declaring DI-1 (tagged by a test) and DI-2 (untagged)."""
    repo = tmp_path / "repo"
    (repo / "dhf" / "documents" / "design").mkdir(parents=True)
    (repo / "dhf" / "documents" / "design" / "alarms.md").write_text(
        "---\n"
        "id: SDS-A-001\n"
        "kind: design\n"
        "context: alarms\n"
        "design_inputs:\n"
        "  - id: DI-1\n"
        '    text: "a tagged input"\n'
        "    traces_to: [UN-001]\n"
        "  - id: DI-2\n"
        '    text: "an untagged input"\n'
        "    traces_to: [UN-001]\n"
        "---\n\n# Alarms\n"
    )
    (repo / "tests").mkdir()
    (repo / "tests" / "test_alarms.py").write_text(
        'import allure\n\n@allure.story("DI-1")\ndef test_one():\n    pass\n'
    )
    return repo


@allure.story("DI-23")
@allure.label("output", "rdm/story_audit/audit.py")
def test_audit_includes_record_first_design_inputs(tmp_path: Path, capsys) -> None:
    """DI-23: with a DHF present, the audit reports per-design-input tag
    coverage, lists untagged inputs as stories without coverage, and reflects
    them in the score; without a DHF, legacy behavior is unchanged."""
    from rdm.story_audit.audit import run_audit, print_report

    repo = _record_first_repo(tmp_path)
    result = run_audit(repo)
    print_report(result, repo)
    out = capsys.readouterr().out

    # Per-design-input test-tag coverage is reported.
    assert "| DI-1 | tagged (1 file(s)) |" in out
    assert "| DI-2 | UNTAGGED |" in out

    # The untagged input is listed as a story without coverage.
    assert "Stories Without Coverage" in out
    assert "- DI-2" in out

    # The score reflects it: 1 of 2 requirement-universe IDs covered -> the
    # coverage criterion is missed (50% < 70%), which a legacy-only scan
    # (0 requirements -> vacuous 100%) would never show.
    assert "- [ ] Coverage 50%" in out

    # Without a DHF, nothing record-first is reported (legacy unchanged).
    (repo / "dhf" / "documents" / "design" / "alarms.md").unlink()
    print_report(run_audit(repo), repo)
    legacy_out = capsys.readouterr().out
    assert "Design inputs (DHF)" not in legacy_out
    assert "DI-2" not in legacy_out
