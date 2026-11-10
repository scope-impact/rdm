"""Acceptance tests for the ingestion context's design inputs (see dhf/).

The tests ("live BDD") that verify DI-16 (code-snippet collection) and DI-17
(foreign test-result translation), tagged `@allure.story`, over the real
`rdm/publishing/collect.py` and `rdm/evidence/translate.py`.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.publishing.collect import collect_from_files, collect_from_lines

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402

_TEST_DATA = Path(__file__).resolve().parents[1] / "test_data"
_GTEST_XML = _TEST_DATA / "test_detail.xml"
_QTTEST_XML = _TEST_DATA / "integration.xml"


@allure.story("DI-16")
@allure.label("output", "rdm/publishing/collect.py")
def test_collects_delimited_code_snippets(tmp_path: Path, capsys) -> None:
    """DI-16: RDOC/ENDRDOC-delimited snippets are extracted, keyed by name."""
    with verification_step("the lines between RDOC and ENDRDOC are extracted under the snippet's key"):
        assert collect_from_lines(["RDOC greeting", "hello", "world", "ENDRDOC"]) == {
            "greeting": "hello\nworld"
        }
    with verification_step("No markers → no snippets"):
        assert collect_from_lines(["just some prose", "no markers here"]) == {}
    with verification_step("a marker is a whole word, never part of a longer one"):
        assert collect_from_lines(["PRDOC_LIMIT = 3", "RDOC limit", "LIMIT = PRDOC_LIMIT", "ENDRDOC"]) == {
            "limit": "LIMIT = PRDOC_LIMIT"}
    with verification_step("a key already collected from another file is refused, naming both files"):
        first, second = tmp_path / "a.py", tmp_path / "b.py"
        first.write_text("# RDOC key\nx = 1\n# ENDRDOC\n")
        second.write_text("# RDOC key\nx = 2\n# ENDRDOC\n")
        with pytest.raises(ValueError, match="key") as refused:
            collect_from_files([str(first), str(second)])
        assert str(first) in str(refused.value) and str(second) in str(refused.value), refused.value
        from rdm.main import cli

        capsys.readouterr()
        assert cli(["collect", str(first), str(second)]) == 2
        assert str(second) in capsys.readouterr().err


