"""Acceptance tests for the gap-analysis context's design inputs (see dhf/).

Each test is the acceptance criterion ("live BDD") for a gap-analysis design
input, tagged `@allure.story("DI-…")`, exercising the real `rdm/gaps.py` engine.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.gaps import audit_for_gaps, coverage_report, list_default_checklists

allure = pytest.importorskip("allure")


@allure.story("DI-10")
@allure.label("output", "rdm/gaps.py")
def test_reports_missing_checklist_references(tmp_path: Path) -> None:
    """DI-10: a missing required reference makes the audit exit non-zero; a fully
    covered document exits zero."""
    checklist = tmp_path / "cl.txt"
    checklist.write_text("X-1 first requirement\nX-2 second requirement\n")

    missing = tmp_path / "partial.md"
    missing.write_text("Document covers [[X-1]] only.\n")
    assert audit_for_gaps(str(checklist), [str(missing)], coverage=False) == 3  # gap → non-zero

    covered = tmp_path / "full.md"
    covered.write_text("Covers [[X-1]] and [[X-2]].\n")
    assert audit_for_gaps(str(checklist), [str(covered)], coverage=False) == 0  # complete → zero


@allure.story("DI-11")
@allure.label("output", "rdm/checklists/")
def test_ships_composable_builtin_checklists(tmp_path: Path, capsys) -> None:
    """DI-11: the standard checklists ship, and a built-in name resolves its
    includes when audited."""
    list_default_checklists()
    listed = capsys.readouterr().out
    for expected in ("62304_2015_class_b", "14971_2019", "FDA-SW_2021_enhanced"):
        assert expected in listed

    # `include` resolution: a key defined ONLY in an included file is still
    # required. If includes were ignored, covering the top-level key alone would
    # pass (0); resolution makes the included B-1 required, so partial → gap (3).
    (tmp_path / "base.txt").write_text("B-1 base requirement\n")
    main = tmp_path / "main.txt"
    main.write_text("include base.txt\nM-1 main requirement\n")
    partial = tmp_path / "partial.md"
    partial.write_text("covers [[M-1]] only\n")
    full = tmp_path / "full.md"
    full.write_text("covers [[M-1]] and [[B-1]]\n")
    assert audit_for_gaps(str(main), [str(partial)], coverage=False) == 3  # included key missing
    assert audit_for_gaps(str(main), [str(full)], coverage=False) == 0     # included key covered


@allure.story("DI-12")
@allure.label("output", "rdm/gaps.py")
def test_coverage_report_tabulates_and_lists_missing(tmp_path: Path, capsys) -> None:
    """DI-12: coverage is tabulated per checklist; verbose names the missing items."""
    checklist = tmp_path / "iso_checklist.txt"
    checklist.write_text("ISO-1 one\nISO-2 two\nISO-3 three\n")
    source = tmp_path / "process.md"
    source.write_text("Document covers [[ISO-1]] and [[ISO-3]].")

    assert coverage_report([str(checklist)], [str(source)]) == 0
    assert "| ISO | 3 | 1 | 2 | 66% |" in capsys.readouterr().out

    # Verbose mode names the missing reference.
    coverage_report([str(checklist)], [str(source)], verbose=True)
    assert "ISO-2" in capsys.readouterr().out
