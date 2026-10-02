"""Tests for AI-persona formative usability-evidence ingest."""

from __future__ import annotations

import json
from pathlib import Path

from rdm.specification.persona import FAILED, parse_runs, reconcile


def _run(results: Path, name: str, user_need: str, outcome: str, issues: int = 0) -> None:
    results.mkdir(parents=True, exist_ok=True)
    payload = {
        "persona": "icu-nurse",
        "user_need": user_need,
        "outcome": outcome,
        "usability_issues": [{"severity": "difficulty", "note": f"n{i}"} for i in range(issues)],
    }
    (results / f"{name}-persona.json").write_text(json.dumps(payload))


class TestParseRuns:


    def test_run_without_user_need_skipped(self, tmp_path: Path) -> None:
        (tmp_path / "x-persona.json").write_text(json.dumps({"persona": "p", "outcome": "success"}))
        assert parse_runs(tmp_path) == []


class TestReconcile:


    def test_failed_dominates(self, tmp_path: Path) -> None:
        _run(tmp_path, "ok", "UN-001", "success")
        _run(tmp_path, "bad", "UN-001", "abandoned")
        assert reconcile({"UN-001"}, tmp_path).by_id["UN-001"].status == FAILED

    def test_not_run_when_no_evidence(self, tmp_path: Path) -> None:
        report = reconcile({"UN-001"}, tmp_path)
        assert report.not_run == ["UN-001"]

    def test_orphan_run_reported(self, tmp_path: Path) -> None:
        _run(tmp_path, "a", "UN-001", "success")
        _run(tmp_path, "b", "UN-999", "success")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.orphan_ids == ["UN-999"]



