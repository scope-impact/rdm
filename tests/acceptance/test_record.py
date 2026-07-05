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
