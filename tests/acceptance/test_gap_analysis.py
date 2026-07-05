"""Acceptance tests for the gap-analysis context's design inputs (see dhf/).

Each test is the acceptance criterion ("live BDD") for a gap-analysis design
input, tagged with `@allure.story` and its DI id, exercising the real
`rdm/gaps.py` engine.

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
    covered document exits zero. A reference is a delimited [[KEY]] — a bare
    prose mention does not count, and a longer key never satisfies a shorter."""
    checklist = tmp_path / "cl.txt"
    checklist.write_text("X-1 first requirement\nX-2 second requirement\n")

    missing = tmp_path / "partial.md"
    missing.write_text("Document covers [[X-1]] only.\n")
    assert audit_for_gaps(str(checklist), [str(missing)], coverage=False) == 3  # gap → non-zero

    covered = tmp_path / "full.md"
    covered.write_text("Covers [[X-1]] and [[X-2]].\n")
    assert audit_for_gaps(str(checklist), [str(covered)], coverage=False) == 0  # complete → zero

    # A bare mention is not a reference: "we do not address X-2" must not
    # count as covering X-2.
    prose = tmp_path / "prose.md"
    prose.write_text("Covers [[X-1]]. We do not address X-2 here.\n")
    assert audit_for_gaps(str(checklist), [str(prose)], coverage=False) == 3

    # Exact key matching: [[X-12]] must not satisfy the key X-1.
    prefix_cl = tmp_path / "prefix_cl.txt"
    prefix_cl.write_text("X-1 first requirement\nX-12 twelfth requirement\n")
    only_longer = tmp_path / "only_longer.md"
    only_longer.write_text("Covers [[X-12]] only.\n")
    assert audit_for_gaps(str(prefix_cl), [str(only_longer)], coverage=False) == 3

    # The `[[KEY: annotation]]` idiom the shipped `rdm init` templates use
    # counts as a reference to KEY — the colon-space tail is prose, not a
    # longer key. But a colon-QUALIFIED key ([[FDA-SW:sdmp]]) still never
    # satisfies its prefix (FDA-SW).
    colon_cl = tmp_path / "colon_cl.txt"
    colon_cl.write_text("FDA-SW:sdmp development and maintenance practices\n")
    annotated = tmp_path / "annotated.md"
    annotated.write_text("[[FDA-SW:sdmp: This document is a pointer document.]]\n")
    assert audit_for_gaps(str(colon_cl), [str(annotated)], coverage=False) == 0

    prefix_colon_cl = tmp_path / "prefix_colon_cl.txt"
    prefix_colon_cl.write_text("FDA-SW parent guidance\nFDA-SW:sdmp practices\n")
    qualified_only = tmp_path / "qualified_only.md"
    qualified_only.write_text("Covers [[FDA-SW:sdmp]] only.\n")
    assert audit_for_gaps(str(prefix_colon_cl), [str(qualified_only)], coverage=False) == 3


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


@allure.story("DI-25")
@allure.label("output", "rdm/checklists/part11_document_control.txt")
def test_rdm_claims_git_as_its_own_document_control(tmp_path: Path, capsys) -> None:
    """DI-25: the Part 11 document-control checklist ships as a built-in, and
    RDM's own document-control statement passes gap analysis against it."""
    import shutil

    # The checklist ships (resolvable by built-in name, not just as a file).
    list_default_checklists()
    assert "part11_document_control" in capsys.readouterr().out

    # RDM's own claim is executable: the statement covers every checklist item.
    statement = Path(__file__).parents[2] / "dhf" / "documents" / "document_control.md"
    assert audit_for_gaps("part11_document_control", [str(statement)], coverage=False) == 0

    # Falsifiable: dropping one control from the statement fails the audit.
    stripped = tmp_path / "statement_missing_audit_trail.md"
    shutil.copy(statement, stripped)
    stripped.write_text(stripped.read_text().replace("[[P11:11.10e]]", ""))
    assert audit_for_gaps("part11_document_control", [str(stripped)], coverage=False) == 3
