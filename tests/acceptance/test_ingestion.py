"""Acceptance tests for the ingestion context's design inputs (see dhf/).

Acceptance criteria ("live BDD") for DI-16 (code-snippet collection) and DI-17
(foreign test-result translation), tagged `@allure.story`, over the real
`rdm/collect.py` and `rdm/translate.py`.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.collect import collect_from_lines
from rdm.translate import translate_test_results
from rdm.util import load_yaml

allure = pytest.importorskip("allure")

_GTEST_XML = Path(__file__).resolve().parents[1] / "test_data" / "test_detail.xml"


@allure.story("DI-16")
@allure.label("output", "rdm/collect.py")
def test_collects_delimited_code_snippets() -> None:
    """DI-16: RDOC/ENDRDOC-delimited snippets are extracted, keyed by name."""
    assert collect_from_lines(["RDOC greeting", "hello", "world", "ENDRDOC"]) == {
        "greeting": "hello\nworld"
    }
    # No markers → no snippets.
    assert collect_from_lines(["just some prose", "no markers here"]) == {}


@allure.story("DI-17")
@allure.label("output", "rdm/translate.py")
def test_translates_foreign_test_results(tmp_path: Path) -> None:
    """DI-17: a gtest XML translates into RDM result data; unknown format rejected."""
    out = tmp_path / "results.yml"
    translate_test_results("gtest", str(_GTEST_XML), str(out))
    results = load_yaml(str(out))
    assert results["SomeModule.Cherry"]["result"] == "pass"
    assert results["HasOneFailure.BadOne"]["result"] == "fail"

    with pytest.raises(ValueError):
        translate_test_results("nonsense-format", str(_GTEST_XML), str(out))
