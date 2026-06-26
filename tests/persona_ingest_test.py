"""Tests for AI-persona formative usability-evidence ingest."""

from __future__ import annotations

import json
from pathlib import Path

from rdm.record.persona import CLEAN, FAILED, ISSUES, parse_runs, reconcile


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
    def test_parses_runs(self, tmp_path: Path) -> None:
        _run(tmp_path, "a", "UN-001", "success")
        runs = parse_runs(tmp_path)
        assert len(runs) == 1 and runs[0].user_need == "UN-001"

    def test_missing_dir_empty(self, tmp_path: Path) -> None:
        assert parse_runs(tmp_path / "nope") == []

    def test_run_without_user_need_skipped(self, tmp_path: Path) -> None:
        (tmp_path / "x-persona.json").write_text(json.dumps({"persona": "p", "outcome": "success"}))
        assert parse_runs(tmp_path) == []


class TestReconcile:
    def test_clean_when_completed_no_issues(self, tmp_path: Path) -> None:
        _run(tmp_path, "a", "UN-001", "success")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == CLEAN

    def test_issues_when_problems_observed(self, tmp_path: Path) -> None:
        _run(tmp_path, "a", "UN-001", "success", issues=2)
        report = reconcile({"UN-001"}, tmp_path)
        need = report.by_id["UN-001"]
        assert need.status == ISSUES and len(need.issues) == 2

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

    def test_clean_is_not_a_pass_claim(self, tmp_path: Path) -> None:
        # "clean" must be a distinct status, never conflated with verified/validated.
        _run(tmp_path, "a", "UN-001", "success")
        assert reconcile({"UN-001"}, tmp_path).by_id["UN-001"].status == CLEAN
        assert CLEAN == "clean"


def test_user_needs_from_vv_plan_doc(tmp_path: Path) -> None:
    from rdm.record.sdd import user_needs_from_doc

    plan = tmp_path / "vv.md"
    plan.write_text(
        "---\nid: VVP-001\nuser_needs:\n"
        "  - {id: UN-001, text: 'a'}\n  - {id: UN-002, text: 'b'}\n---\n\nbody\n"
    )
    assert user_needs_from_doc(plan) == {"UN-001", "UN-002"}
