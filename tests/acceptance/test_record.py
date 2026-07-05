"""Acceptance test for the record context's DI-29 (see dhf/).

The DMR index generator: device-master-record data derived from the controlled
documents' own frontmatter. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from rdm.record.dmr import dmr_command

allure = pytest.importorskip("allure")


@allure.story("DI-29")
@allure.label("output", "rdm/record/dmr.py")
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

    # The output is marked generated, and regenerating is deterministic.
    first = out.read_text()
    assert "GENERATED" in first
    assert dmr_command(docs, out) == 0
    assert out.read_text() == first


@allure.story("DI-31")
@allure.label("output", "rdm/record/allure.py")
def test_polyglot_test_sources_are_discovered(tmp_path: Path) -> None:
    """DI-31: JS/TS allure calls and Java annotations are discovered across
    conventional test-file names; Python keeps function-scope source capture
    while other languages pin the whole file."""
    from rdm.record.allure import scan_source_tags, scan_tagged_sources

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
        "  expect(fire()).toBe(true);\n"
        "});\n"
    )
    (tests / "AlarmTest.java").write_text(
        "import io.qameta.allure.Story;\n\n"
        "public class AlarmTest {\n"
        '  @Story("DI-3")\n'
        "  @Test\n  void alarmFires() { assertTrue(fire()); }\n"
        "}\n"
    )
    (tests / "notes.txt").write_text('allure.story("DI-9") mentioned in prose\n')

    # Every language's tag is discovered; the non-test file is not scanned.
    tags = scan_source_tags(tests)
    assert set(tags) == {"DI-1", "DI-2", "DI-3"}
    assert tags["DI-2"] == [str(tests / "alarms.test.ts")]
    assert tags["DI-3"] == [str(tests / "AlarmTest.java")]

    # Function scope for Python (the helper is OUTSIDE the pinned source);
    # whole-file scope for the other languages.
    sources = scan_tagged_sources(tests)
    assert "def test_py" in sources["DI-1"][0] and "def helper" not in sources["DI-1"][0]
    assert sources["DI-2"] == [(tests / "alarms.test.ts").read_text()]
    assert sources["DI-3"] == [(tests / "AlarmTest.java").read_text()]


@allure.story("DI-34")
@allure.label("output", "rdm/record/allure.py")
def test_ansible_test_task_tags_are_discovered(tmp_path: Path) -> None:
    """DI-34: Ansible test task files (*_test.yml / *_test.yaml) are scanned;
    ID-shaped task tags count as story tags, operational tags are ignored,
    non-test YAML is not scanned, and the source pins at whole-file scope."""
    from rdm.record.allure import scan_source_tags, scan_tagged_sources

    tests = tmp_path / "tests"
    (tests / "ansible").mkdir(parents=True)
    state_test = tests / "ansible" / "state_management_test.yml"
    state_test.write_text(
        "- name: stack roots use an S3 backend\n"
        "  ansible.builtin.assert:\n"
        "    that: \"'backend = \\\"s3\\\"' in root_hcl\"\n"
        "  tags: [DI-7, always]\n"
        "- name: state locking enabled\n"
        "  ansible.builtin.assert:\n"
        "    that: \"'use_lockfile = true' in root_hcl\"\n"
        "  tags:\n"
        "    - DI-8\n"
        "    - bootstrap\n"
    )
    (tests / "ansible" / "playbook_test.yaml").write_text(
        "- hosts: localhost\n"
        "  tasks:\n"
        "    - name: nested play task tag is found\n"
        "      ansible.builtin.assert:\n"
        "        that: true\n"
        "      tags: DI-9\n"
    )
    (tests / "ansible" / "group_vars.yml").write_text(
        "tags: [DI-6]\n"  # not *_test.yml -> never scanned
    )

    # ID-shaped tags are discovered in both list and scalar/block forms;
    # operational tags and non-test YAML files are not.
    tags = scan_source_tags(tests)
    assert set(tags) == {"DI-7", "DI-8", "DI-9"}
    assert tags["DI-7"] == [str(state_test)]
    assert "always" not in tags and "bootstrap" not in tags

    # Whole-file scope: the pinned source is the full task file.
    sources = scan_tagged_sources(tests)
    assert sources["DI-7"] == [state_test.read_text()]
    assert sources["DI-8"] == [state_test.read_text()]
