"""Tests for the Allure results ingester (system-of-record verification)."""

from __future__ import annotations

import json
from pathlib import Path

from rdm.record.allure import (
    FAILED,
    UNTESTED,
    VERIFIED,
    parse_results,
    reconcile,
)


def _result(results_dir: Path, name: str, status: str, *user_need_ids: str) -> None:
    labels = [{"name": "story", "value": uid} for uid in user_need_ids]
    payload = {"name": name, "status": status, "labels": labels}
    (results_dir / f"{name}-result.json").write_text(json.dumps(payload))


class TestParseResults:
    def test_parses_status_and_user_need_labels(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001", "UN-002")
        results = parse_results(tmp_path)
        assert len(results) == 1
        assert results[0].status == "passed"
        assert set(results[0].user_need_ids) == {"UN-001", "UN-002"}

    def test_missing_dir_returns_empty(self, tmp_path: Path) -> None:
        assert parse_results(tmp_path / "nope") == []

    def test_skips_malformed_json(self, tmp_path: Path) -> None:
        (tmp_path / "bad-result.json").write_text("{not json")
        _result(tmp_path, "ok", "passed", "UN-001")
        assert len(parse_results(tmp_path)) == 1


class TestReconcile:
    def test_passing_test_verifies_user_need(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_user_need["UN-001"].status == VERIFIED
        assert report.verified == ["UN-001"]

    def test_failing_test_fails_user_need(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "failed", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_user_need["UN-001"].status == FAILED

    def test_failure_dominates_a_passing_test(self, tmp_path: Path) -> None:
        _result(tmp_path, "ok", "passed", "UN-001")
        _result(tmp_path, "bad", "broken", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_user_need["UN-001"].status == FAILED

    def test_declared_need_with_no_test_is_untested(self, tmp_path: Path) -> None:
        report = reconcile({"UN-001"}, tmp_path)
        assert report.untested == ["UN-001"]

    def test_skipped_only_is_untested(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "skipped", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_user_need["UN-001"].status == UNTESTED

    def test_orphan_tag_reported(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001")
        _result(tmp_path, "t2", "passed", "UN-999")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.orphan_ids == ["UN-999"]
