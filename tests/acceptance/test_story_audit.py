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
def test_audit_includes_record_first_design_inputs(tmp_path: Path, capsys, monkeypatch) -> None:
    """DI-23: with a DHF present, the audit reports per-design-input tag
    coverage, lists untagged inputs as stories without coverage, and reflects
    them in the score; the scan is anchored to the audited repository (the
    caller's cwd must never poison coverage); without a DHF, legacy behavior
    is unchanged."""
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

    # The scan is anchored to the AUDITED repository, never the caller's cwd:
    # auditing a repo with no test suite, from a cwd whose own tests/ tags the
    # very same DI ids, must report every input UNTAGGED — the old <cwd>/tests
    # fallback imported the caller's tags as the audited repo's coverage.
    import shutil

    bare = tmp_path / "bare"
    shutil.copytree(repo / "dhf", bare / "dhf")          # same DHF, no tests/
    decoy_home = tmp_path / "decoy_home"
    (decoy_home / "tests").mkdir(parents=True)
    (decoy_home / "tests" / "test_poison.py").write_text(
        'import allure\n\n@allure.story("DI-1")\ndef test_a():\n    pass\n\n'
        '@allure.story("DI-2")\ndef test_b():\n    pass\n'
    )
    monkeypatch.chdir(decoy_home)
    print_report(run_audit(bare), bare)
    poisoned_check = capsys.readouterr().out
    assert "| DI-1 | UNTAGGED |" in poisoned_check
    assert "| DI-2 | UNTAGGED |" in poisoned_check

    # Within a repository the walk-up is bounded by the repo root: a nested
    # DHF finds the repo's tests/, and a tests/ outside the boundary is unseen.
    from rdm.record.allure import find_tests_dir

    nested = tmp_path / "gitrepo"
    (nested / ".git").mkdir(parents=True)
    (nested / "tests").mkdir()
    (nested / "product" / "dhf").mkdir(parents=True)
    assert find_tests_dir(nested / "product" / "dhf") == nested / "tests"
    fenced = tmp_path / "gitrepo2"
    (fenced / ".git").mkdir(parents=True)
    (fenced / "dhf").mkdir(parents=True)
    (tmp_path / "tests").mkdir()                         # outside the repo
    assert find_tests_dir(fenced / "dhf") is None

    # Without a DHF, nothing record-first is reported (legacy unchanged).
    (repo / "dhf" / "documents" / "design" / "alarms.md").unlink()
    print_report(run_audit(repo), repo)
    legacy_out = capsys.readouterr().out
    assert "Design inputs (DHF)" not in legacy_out
    assert "DI-2" not in legacy_out


@allure.story("DI-32")
@allure.label("output", "rdm/story_audit/validate.py")
def test_legacy_yaml_workflow_is_deprecated(tmp_path: Path, monkeypatch, capsys) -> None:
    """DI-32: validate and check-ids print a deprecation notice naming the
    record-first replacement, and stay functional with unchanged exit codes."""
    from rdm.story_audit.check_ids import story_check_ids_command
    from rdm.story_audit.validate import story_validate_command

    good = tmp_path / "requirements"
    good.mkdir()
    (good / "ft-001.yaml").write_text("id: FT-001\n")
    monkeypatch.chdir(tmp_path)

    # check-ids: notice on stderr, names the replacement, exit code unchanged.
    assert story_check_ids_command([good / "ft-001.yaml"]) == 0
    err = capsys.readouterr().err
    assert "DEPRECATED" in err and "record-first" in err

    dup = good / "dup.yaml"
    dup.write_text("id: FT-001\n")
    assert story_check_ids_command([good / "ft-001.yaml", dup]) == 1  # still detects

    # validate: same notice, still functional against the requirements dir.
    assert story_validate_command(requirements_dir=good) in (0, 1)
    err = capsys.readouterr().err
    assert "DEPRECATED" in err and "rdm story validate" in err
