"""Acceptance test for the record context's DI-29 (see dhf/).

The DMR index generator: device-master-record data derived from the controlled
documents' own frontmatter. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from rdm.publishing.dmr import dmr_command

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402


@allure.story("DI-29")
@allure.label("output", "rdm/publishing/dmr.py")
def test_dmr_index_data_is_generated_from_frontmatter(tmp_path: Path, capsys) -> None:
    """DI-29: one entry per controlled document (id, title, path, revision),
    generated from frontmatter; an un-identified document is not indexed."""
    docs = tmp_path / "documents"
    docs.mkdir()
    (docs / "sop.md").write_text('---\nid: SOP-1\nrevision: 2\ntitle: "The SOP"\n---\nbody\n')
    (docs / "plan.md").write_text('---\nid: PLAN-1\nrevision: 1\ntitle: "The Plan"\n---\nbody\n')
    (docs / "notes.md").write_text("just notes, no frontmatter\n")

    out = tmp_path / "data" / "dmr.yml"
    assert dmr_command(docs, out) == 0
    captured = capsys.readouterr()
    assert "notes.md" in captured.err  # un-identified document skipped, loudly

    data = yaml.safe_load(out.read_text())
    assert data["entries"] == [
        {"id": "PLAN-1", "title": "The Plan", "path": "documents/plan.md", "revision": 1},
        {"id": "SOP-1", "title": "The SOP", "path": "documents/sop.md", "revision": 2},
    ]

    with verification_step("A blank id is no id: the document is skipped, never indexed as None"):
        blank = tmp_path / "blank"
        blank.mkdir()
        (blank / "a.md").write_text("---\nid:\ntitle: A\n---\n")
        (blank / "b.md").write_text("---\nid: ''\n---\n")
        assert dmr_command(blank, tmp_path / "blank.yml") == 1
        assert "a.md" in capsys.readouterr().err
    with verification_step("The output is marked generated, and regenerating is deterministic"):
        first = out.read_text()
        assert "GENERATED" in first
        assert dmr_command(docs, out) == 0
        assert out.read_text() == first


@allure.story("DI-31")
@allure.label("output", "rdm/evidence/allure.py")
def test_polyglot_test_sources_are_discovered(tmp_path: Path) -> None:
    """DI-31: JS/TS allure.story calls and Java @Story annotations are
    discovered across conventional test-file names; features name no input."""
    from rdm.specification.tags import scan_source_tags

    tests = tmp_path / "tests"
    tests.mkdir()
    (tests / "test_core.py").write_text(
        "import allure\n\n"
        "def helper():\n    return 1\n\n"
        '@allure.story("DI-1")\ndef test_py():\n    assert helper() == 1\n'
    )
    (tests / "alarms.test.ts").write_text(
        "import { allure } from 'allure-playwright';\n"
        "test('alarm fires', async () => {\n"
        "  await allure.story('DI-2');\n"
        "  await allure.feature('DI-7');\n"
        "  expect(fire()).toBe(true);\n"
        "});\n"
    )
    (tests / "AlarmTest.java").write_text(
        "import io.qameta.allure.Story;\n\n"
        "public class AlarmTest {\n"
        '  @Story("DI-3")\n'
        '  @Feature("DI-8")\n'
        "  @Test\n  void alarmFires() { assertTrue(fire()); }\n"
        "}\n"
    )
    (tests / "notes.txt").write_text('allure.story("DI-9") mentioned in prose\n')

    with verification_step("Every language's story tag is discovered; features and the non-test file are not"):
        tags = scan_source_tags(tests)
        assert set(tags) == {"DI-1", "DI-2", "DI-3"}
        assert tags["DI-2"] == [str(tests / "alarms.test.ts")]
        assert tags["DI-3"] == [str(tests / "AlarmTest.java")]
