"""Acceptance test for the validation context's DI-33 (see dhf/).

Summative validation records: ingested per user need, with the release gate
naming every need lacking an approved record — as a warning, never a blocker
(validation is human-evidenced). Skips cleanly if allure-pytest is absent.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.record.faithfulness import hash_for
from rdm.record.validation import parse_validation_records, unvalidated_user_needs
from rdm.story_audit.design_gate import run_release_gate
from tests.util import git_run

allure = pytest.importorskip("allure")


def _validated_record(tmp_path: Path) -> tuple[Path, Path]:
    """A minimal passing record: two user needs, one DI each, verified + faithful."""
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "vv_plan.md").write_text(
        "---\nuser_needs:\n"
        "  - {id: UN-001, text: 'a need'}\n"
        "  - {id: UN-002, text: 'another need'}\n---\n"
    )
    (dhf / "documents" / "design" / "core.md").write_text(
        "---\nid: SDS-C-001\nkind: design\ncontext: core\n"
        "satisfies: [UN-001, UN-002]\n"
        "design_inputs:\n"
        "  - id: DI-1\n    text: 'req one'\n    traces_to: [UN-001]\n"
        "  - id: DI-2\n    text: 'req two'\n    traces_to: [UN-002]\n---\n\n# Core\n"
    )
    (dhf / "documents" / "design_review.md").write_text(
        "---\nid: DR-001\n---\n# Review\nApproved.\n"
    )
    (dhf / "faithfulness").mkdir()
    (tmp_path / "tests").mkdir()  # empty sibling tests dir: deterministic hashes
    results = tmp_path / "allure-results"
    results.mkdir()
    for di, text in (("DI-1", "req one"), ("DI-2", "req two")):
        (results / f"{di}-result.json").write_text(json.dumps(
            {"name": di, "status": "passed",
             "labels": [{"name": "story", "value": di}]}
        ))
        (dhf / "faithfulness" / f"{di}-faithfulness.json").write_text(json.dumps(
            {"design_input": di, "verdict": "faithful", "reviewer": "r2",
             "rationale": "x", "test_hash": hash_for(text, []),
             "hash_scope": "function"}
        ))
    git_run(tmp_path, "init")
    git_run(tmp_path, "add", "-A")
    git_run(tmp_path, "commit", "-m", "approve design")
    return dhf, results


@allure.story("DI-33")
@allure.label("output", "rdm/record/validation.py")
def test_release_gate_names_unvalidated_user_needs(tmp_path: Path) -> None:
    """DI-33: validation records are ingested per user need; the release gate
    warns (never blocks) for each need without an APPROVED record."""
    dhf, results = _validated_record(tmp_path)

    # No validation records at all: every need is named, as a warning only.
    gate = run_release_gate(dhf, results)
    validation_warnings = [w for w in gate.warnings if "validation record" in w]
    assert any("UN-001" in w for w in validation_warnings)
    assert any("UN-002" in w for w in validation_warnings)
    assert gate.passed  # warnings never block

    # An APPROVED record for UN-001 clears its warning; a non-approved
    # disposition for UN-002 does not.
    validation = dhf / "validation"
    validation.mkdir()
    (validation / "UN-001-validation.json").write_text(json.dumps(
        {"user_need": "UN-001", "disposition": "approved",
         "reviewer": "maintainer (summative)", "summary": "journey reviewed"}
    ))
    (validation / "UN-002-validation.json").write_text(json.dumps(
        {"user_need": "UN-002", "disposition": "pending", "reviewer": "maintainer"}
    ))
    gate = run_release_gate(dhf, results)
    validation_warnings = [w for w in gate.warnings if "validation record" in w]
    assert not any("UN-001" in w for w in validation_warnings)
    assert any("UN-002" in w for w in validation_warnings)
    assert gate.passed

    # The ingest itself: records keyed by user need, disposition and reviewer read.
    records = parse_validation_records(validation)
    assert records["UN-001"]["disposition"] == "approved"
    assert records["UN-001"]["reviewer"] == "maintainer (summative)"
    assert unvalidated_user_needs(dhf) == ["UN-002"]
