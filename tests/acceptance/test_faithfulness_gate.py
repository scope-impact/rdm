"""Acceptance test for the faithfulness gate's design input (see dhf/).

Acceptance criterion ("live BDD") for DI-19, tagged `@allure.story`, over the
real `faithfulness.reconcile`: only a current, fully-covered verdict is
`faithful`; unreviewed / unfaithful / partial / stale are all detected (→ the
release gate blocks them).

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.record import faithfulness as f
from rdm.story_audit.design_gate import _faithfulness_messages

allure = pytest.importorskip("allure")


def _verdict(vdir: Path, di: str, verdict: str, test_hash: str = "", **extra) -> None:
    vdir.mkdir(parents=True, exist_ok=True)
    body = {"design_input": di, "verdict": verdict, "test_hash": test_hash}
    body.update(extra)
    (vdir / f"{di}-faithfulness.json").write_text(json.dumps(body))


@allure.story("DI-19")
@allure.label("output", "rdm/record/faithfulness.py")
def test_faithfulness_gate_classifies_and_blocks(tmp_path: Path) -> None:
    """DI-19: only a current, fully-covered verdict is faithful; the other four
    states are detected and would block the release gate."""
    inputs = [
        {"id": "FA-1", "text": "clean", "traces_to": []},        # faithful
        {"id": "FA-2", "text": "unfaithful", "traces_to": []},   # unfaithful
        {"id": "FA-3", "text": "partial", "traces_to": []},      # partial (uncovered clause)
        {"id": "FA-4", "text": "stale", "traces_to": []},        # stale (wrong hash)
        {"id": "FA-5", "text": "unreviewed", "traces_to": []},   # no verdict
    ]
    cur = f.current_hashes(inputs, None)  # tests_dir=None → hash over the text only
    vdir = tmp_path / "verdicts"
    _verdict(vdir, "FA-1", "faithful", test_hash=cur["FA-1"])
    _verdict(vdir, "FA-2", "unfaithful", test_hash=cur["FA-2"])
    _verdict(vdir, "FA-3", "faithful", test_hash=cur["FA-3"],
             uncovered_clauses=["clause 2 is untested"])  # faithful + uncovered → partial
    _verdict(vdir, "FA-4", "faithful", test_hash="sha256:not-the-current-hash")  # → stale
    # FA-5: no verdict file → unreviewed

    report = f.reconcile(inputs, vdir, None)
    assert report.faithful == ["FA-1"]
    assert report.unfaithful == ["FA-2"]
    assert report.partial == ["FA-3"]
    assert report.stale == ["FA-4"]
    assert report.unreviewed == ["FA-5"]

    # The gate blocks on every non-faithful state (one message each), and never
    # on the faithful one.
    blocking = _faithfulness_messages(report)
    blocked_ids = {di for di in ("FA-2", "FA-3", "FA-4", "FA-5") if any(di in m for m in blocking)}
    assert blocked_ids == {"FA-2", "FA-3", "FA-4", "FA-5"}
    assert not any("FA-1" in m for m in blocking)
