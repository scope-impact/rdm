"""Tests for the Allure results ingester (system-of-record verification)."""

from __future__ import annotations

from pathlib import Path

from rdm.evidence.allure import parse_results
from tests.util import write_allure_result as _result


class TestParseResults:


    def test_skips_malformed_json(self, tmp_path: Path) -> None:
        (tmp_path / "bad-result.json").write_text("{not json")
        _result(tmp_path, "ok", "passed", "UN-001")
        assert len(parse_results(tmp_path)) == 1
