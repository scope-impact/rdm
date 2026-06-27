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
from tests.util import write_allure_result as _result


def _result_with_output(results_dir: Path, name: str, status: str, story: str, output: str) -> None:
    """Write a result tagging a design input (story) and a design output (label)."""
    results_dir.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "story", "value": story}, {"name": "output", "value": output}]
    (results_dir / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


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

    def test_parses_output_label(self, tmp_path: Path) -> None:
        _result_with_output(tmp_path, "t1", "passed", "DI-1", "SDS-core")
        results = parse_results(tmp_path)
        assert results[0].user_need_ids == ["DI-1"]
        assert results[0].outputs == ["SDS-core"]


class TestReconcile:
    def test_passing_test_verifies_user_need(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == VERIFIED
        assert report.verified == ["UN-001"]

    def test_failing_test_fails_user_need(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "failed", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == FAILED

    def test_failure_dominates_a_passing_test(self, tmp_path: Path) -> None:
        _result(tmp_path, "ok", "passed", "UN-001")
        _result(tmp_path, "bad", "broken", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == FAILED

    def test_declared_need_with_no_test_is_untested(self, tmp_path: Path) -> None:
        report = reconcile({"UN-001"}, tmp_path)
        assert report.untested == ["UN-001"]

    def test_skipped_only_is_untested(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "skipped", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == UNTESTED

    def test_orphan_tag_reported(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001")
        _result(tmp_path, "t2", "passed", "UN-999")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.orphan_ids == ["UN-999"]

    def test_outputs_aggregated_onto_verification(self, tmp_path: Path) -> None:
        # Design outputs from the `output` label surface on the design input's
        # verification, deduped across covering tests.
        _result_with_output(tmp_path, "t1", "passed", "DI-1", "SDS-core")
        _result_with_output(tmp_path, "t2", "passed", "DI-1", "SDS-core")
        report = reconcile({"DI-1"}, tmp_path)
        assert report.by_id["DI-1"].outputs == ["SDS-core"]
