"""The PM pipeline produces planning artifacts, stamped as non-record."""

from __future__ import annotations

from types import SimpleNamespace

from rdm.project_management.sync import (
    PROVENANCE_NOTE,
    build_subtask_body,
    build_task_body,
)


def _task(**kw):
    base = dict(
        id="rdm-001",
        description="Do the thing",
        business_value="",
        acceptance_criteria=[],
        subtask_ids=[],
        priority="high",
    )
    base.update(kw)
    return SimpleNamespace(**base)


def test_provenance_note_marks_data_as_non_record():
    assert "not a controlled record" in PROVENANCE_NOTE


def test_task_body_carries_provenance_stamp():
    body = build_task_body(_task())
    assert PROVENANCE_NOTE in body


def test_subtask_body_carries_provenance_stamp():
    subtask = _task(id="rdm-001.01", priority="medium")
    body = build_subtask_body(subtask, parent_issue_number=7)
    assert PROVENANCE_NOTE in body
