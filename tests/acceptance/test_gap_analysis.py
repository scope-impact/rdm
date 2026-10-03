"""Acceptance tests for the gap-analysis context's design inputs (see dhf/).

Each test ("live BDD") verifies a gap-analysis design
input, tagged with `@allure.story` and its DI id, exercising the real
`rdm/compliance/gaps.py` engine.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

from rdm.compliance.gaps import (
    audit_for_gaps,
    builtin_checklists,
    coverage_report,
    list_default_checklists,
    missing_references,
    parse_checklist,
)

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402


@allure.story("DI-10")
@allure.label("output", "rdm/compliance/gaps.py")
def test_reports_missing_checklist_references(tmp_path: Path) -> None:
    """DI-10: a missing required reference makes the audit exit non-zero; a fully
    covered document exits zero. A reference is a delimited [[KEY]] — a bare
    prose mention does not count, and a longer key never satisfies a shorter."""
    checklist = tmp_path / "cl.txt"
    checklist.write_text("X-1 first requirement\nX-2 second requirement\n")

    missing = tmp_path / "partial.md"
    missing.write_text("Document covers [[X-1]] only.\n")
    covered = tmp_path / "full.md"
    covered.write_text("Covers [[X-1]] and [[X-2]].\n")
    with verification_step("a missing reference is listed and exits 3; a document covering every clause exits 0"):
        gap = subprocess.run([sys.executable, "-m", "rdm.main", "gap", str(checklist), str(missing)],
                             capture_output=True, text=True)
        assert gap.returncode == 3 and "X-2" in gap.stdout and "X-1" not in gap.stdout, gap.stdout
        assert audit_for_gaps(str(checklist), [str(missing)]) == 3
        assert audit_for_gaps(str(checklist), [str(covered)]) == 0
    with verification_step("a stray [[ does not turn the next paragraphs' mentions into references"):
        stray = tmp_path / "stray.md"
        stray.write_text("Covers [[X-1]]. A stray [[ here.\n\nX-2 is only mentioned. ]]\n")
        assert audit_for_gaps(str(checklist), [str(stray)]) == 3

    with verification_step("A bare mention is not a reference: \"we do not address X-2\" must not count as covering "
                           "X-2"):
        prose = tmp_path / "prose.md"
        prose.write_text("Covers [[X-1]]. We do not address X-2 here.\n")
        assert audit_for_gaps(str(checklist), [str(prose)]) == 3

    with verification_step("Exact key matching: [[X-12]] must not satisfy the key X-1"):
        prefix_cl = tmp_path / "prefix_cl.txt"
        prefix_cl.write_text("X-1 first requirement\nX-12 twelfth requirement\n")
        only_longer = tmp_path / "only_longer.md"
        only_longer.write_text("Covers [[X-12]] only.\n")
        assert audit_for_gaps(str(prefix_cl), [str(only_longer)]) == 3

    # The `[[KEY: annotation]]` idiom the shipped `rdm init` templates use
    # counts as a reference to KEY — the colon-space tail is prose, not a
    # longer key. But a colon-QUALIFIED key ([[FDA-SW:sdmp]]) still never
    # satisfies its prefix (FDA-SW).
    with verification_step("The `[[KEY: annotation]]` idiom the shipped `rdm init` templates use counts as a "
                           "reference to…"):
        colon_cl = tmp_path / "colon_cl.txt"
        colon_cl.write_text("FDA-SW:sdmp development and maintenance practices\n")
        annotated = tmp_path / "annotated.md"
        annotated.write_text("[[FDA-SW:sdmp: This document is a pointer document.]]\n")
        assert audit_for_gaps(str(colon_cl), [str(annotated)]) == 0

        prefix_colon_cl = tmp_path / "prefix_colon_cl.txt"
        prefix_colon_cl.write_text("FDA-SW parent guidance\nFDA-SW:sdmp practices\n")
        qualified_only = tmp_path / "qualified_only.md"
        qualified_only.write_text("Covers [[FDA-SW:sdmp]] only.\n")
        assert audit_for_gaps(str(prefix_colon_cl), [str(qualified_only)]) == 3

    with verification_step("nothing checked is an error, never success: a checklist with no clauses, a checklist, "
                           "include or document that cannot be read, a bare include"):
        empty = tmp_path / "empty.txt"
        empty.write_text("# only a header\n")
        assert audit_for_gaps(str(empty), [str(covered)]) == 2
        assert audit_for_gaps(str(tmp_path / "nope.txt"), [str(covered)]) == 2
        bare = tmp_path / "bare.txt"
        bare.write_text("include\nX-1 one\n")
        assert audit_for_gaps(str(bare), [str(covered)]) == 2
        lost = tmp_path / "lost.txt"
        lost.write_text("include gone.txt\nX-1 one\n")
        assert audit_for_gaps(str(lost), [str(covered)]) == 2
        assert audit_for_gaps(str(checklist), [str(tmp_path / "missing.md")]) == 2
        assert audit_for_gaps(str(checklist), [str(tmp_path)]) == 2
        spaced = tmp_path / "spaced.txt"
        spaced.write_text("include   cl.txt  \n")
        assert audit_for_gaps(str(spaced), [str(covered)]) == 0

    with verification_step("a gap is named as the glossary names it"):
        out = subprocess.run([sys.executable, "-m", "rdm.main", "gap", str(checklist), str(missing)],
                             capture_output=True, text=True).stdout
        assert "# 1 gap:" in out and "Missing" not in out, out
    with verification_step("a byte-order mark is not part of the first key"):
        bom = tmp_path / "bom.txt"
        bom.write_text("\ufeffX-1 first\n", encoding="utf-8")
        assert audit_for_gaps(str(bom), [str(covered)]) == 0

    with verification_step("a document that still holds a placeholder is named in a warning"):
        draft = tmp_path / "draft.md"
        draft.write_text("Covers [[X-1]] and [[X-2]].\n\nTODO: write this section\nENDTODO\n")
        out = subprocess.run([sys.executable, "-m", "rdm.main", "gap", str(checklist), str(draft)],
                             capture_output=True, text=True)
        assert out.returncode == 0 and "draft.md" in out.stdout and "placeholder" in out.stdout, out.stdout

    with verification_step("a report piped into a reader that stops early ends quietly, without a traceback"):
        many = tmp_path / "many.txt"
        many.write_text("".join(f"K-{i} clause {i}\n" for i in range(20000)))
        piped = subprocess.run(f'"{sys.executable}" -m rdm.main gap "{many}" "{missing}" | head -1', shell=True,
                               capture_output=True, text=True)
        assert "Traceback" not in piped.stderr and "BrokenPipe" not in piped.stderr, piped.stderr


@allure.story("DI-11")
@allure.label("output", "rdm/compliance/checklists/")
def test_ships_composable_builtin_checklists(tmp_path: Path, capsys) -> None:
    """DI-11: the standard checklists ship, and a built-in name resolves its
    includes when audited."""
    with verification_step("the built-in checklists are listed by name"):
        list_default_checklists()
        listed = capsys.readouterr().out
        for expected in ("62304_2015_class_b", "14971_2019", "FDA-SW_2021_enhanced"):
            assert expected in listed

    with verification_step("IEC 62304, ISO 14971, FDA-SW, FDA-CYBER and FDA-HFE each have a built-in with clauses"):
        names = builtin_checklists()
        for standard in ("62304_", "14971_", "FDA-SW_", "FDA-CYBER_", "FDA-HFE_"):
            shipped = [name for name in names if name.startswith(standard)]
            assert shipped, standard
            for name in shipped:
                assert missing_references(name, [])[1], name

    with verification_step("every built-in checklist's keys are unique"):
        for name, path in builtin_checklists().items():
            entries = parse_checklist(Path(path).read_text(encoding="utf-8"), Path(path).parent)
            keys = [e["reference"] for e in entries if "reference" in e]
            assert len(keys) == len(set(keys)), name

    with verification_step("a built-in that includes another by name counts the included checklist's clauses"):
        class_b = {item["reference"] for item in missing_references("62304_2015_class_b", [])[1]}
        class_a = {item["reference"] for item in missing_references("62304_2015_class_a", [])[1]}
        assert class_a < class_b

    # `include` resolution: a key defined ONLY in an included file is still
    # required. If includes were ignored, covering the top-level key alone would
    # pass (0); resolution makes the included B-1 required, so partial → gap (3).
    with verification_step("`include` resolution: a key defined ONLY in an included file is still required. If "
                           "includes were…"):
        (tmp_path / "base.txt").write_text("B-1 base requirement\n")
        main = tmp_path / "main.txt"
        main.write_text("include base.txt\nM-1 main requirement\n")
        partial = tmp_path / "partial.md"
        partial.write_text("covers [[M-1]] only\n")
        full = tmp_path / "full.md"
        full.write_text("covers [[M-1]] and [[B-1]]\n")
        assert audit_for_gaps(str(main), [str(partial)]) == 3  # included key missing
        assert audit_for_gaps(str(main), [str(full)]) == 0     # included key covered
    with verification_step("a checklist reached by two spellings of its path is read once"):
        (tmp_path / "sub").mkdir()
        (tmp_path / "a.txt").write_text("include sub/b.txt\nA-1 a\n")
        (tmp_path / "sub" / "b.txt").write_text("include ../a.txt\ninclude ./../a.txt\nB-2 b\n")
        only_a = tmp_path / "only_a.md"
        only_a.write_text("covers [[A-1]]\n")
        capsys.readouterr()
        assert coverage_report([str(tmp_path / "a.txt")], [str(only_a)]) == 3
        assert "| A.TXT | 2 | 1 | 1 | 50% |" in capsys.readouterr().out


@allure.story("DI-12")
@allure.label("output", "rdm/compliance/gaps.py")
def test_coverage_report_tabulates_and_lists_missing(tmp_path: Path, capsys) -> None:
    """DI-12: coverage is tabulated per checklist; verbose names the missing items."""
    checklist = tmp_path / "iso_checklist.txt"
    checklist.write_text("ISO-1 one\nISO-2 two\nISO-3 three\n")
    source = tmp_path / "process.md"
    source.write_text("Document covers [[ISO-1]] and [[ISO-3]].")

    with verification_step("a checklist's row gives total, missing, covered and percent, and a gap exits 3"):
        assert coverage_report([str(checklist)], [str(source)]) == 3
        assert "| ISO | 3 | 1 | 2 | 66% |" in capsys.readouterr().out
    with verification_step("coverage exits as the audit does: 0 when complete, 2 when nothing could be checked"):
        complete = tmp_path / "complete.md"
        complete.write_text("[[ISO-1]] [[ISO-2]] [[ISO-3]]\n")
        assert coverage_report([str(checklist)], [str(complete)]) == 0
        empty = tmp_path / "empty.txt"
        empty.write_text("# only a header\n")
        assert coverage_report([str(empty)], [str(complete)]) == 2
        assert coverage_report([str(tmp_path / "nope.txt")], [str(complete)]) == 2
        capsys.readouterr()

    with verification_step("Verbose mode names the missing reference"):
        coverage_report([str(checklist)], [str(source)], verbose=True)
        assert "ISO-2" in capsys.readouterr().out
    with verification_step("with nothing missing, verbose mode prints no empty heading"):
        coverage_report([str(checklist)], [str(complete)], verbose=True)
        assert "Missing Items" not in capsys.readouterr().out


@allure.story("DI-25")
@allure.label("output", "rdm/compliance/checklists/part11_document_control.txt")
def test_rdm_claims_git_as_its_own_document_control(tmp_path: Path, capsys) -> None:
    """DI-25: the Part 11 document-control checklist ships as a built-in, and
    RDM's own document-control statement passes gap analysis against it."""
    import shutil

    with verification_step("The checklist ships (resolvable by built-in name, not just as a file)"):
        list_default_checklists()
        assert "part11_document_control" in capsys.readouterr().out

    with verification_step("RDM's own claim is executable: the statement covers every checklist item"):
        statement = Path(__file__).parents[2] / "dhf" / "documents" / "document_control.md"
        assert audit_for_gaps("part11_document_control", [str(statement)]) == 0

    with verification_step("Falsifiable: dropping one control from the statement fails the audit"):
        stripped = tmp_path / "statement_missing_audit_trail.md"
        shutil.copy(statement, stripped)
        stripped.write_text(stripped.read_text().replace("[[P11:11.10e]]", ""))
        assert audit_for_gaps("part11_document_control", [str(stripped)]) == 3
