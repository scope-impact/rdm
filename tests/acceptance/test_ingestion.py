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

from rdm.publishing.collect import collect_from_lines
from rdm.evidence.translate import translate_test_results
from rdm.kernel.util import load_yaml

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402

_TEST_DATA = Path(__file__).resolve().parents[1] / "test_data"
_GTEST_XML = _TEST_DATA / "test_detail.xml"
_QTTEST_XML = _TEST_DATA / "integration.xml"


@allure.story("DI-16")
@allure.label("output", "rdm/publishing/collect.py")
def test_collects_delimited_code_snippets() -> None:
    """DI-16: RDOC/ENDRDOC-delimited snippets are extracted, keyed by name."""
    assert collect_from_lines(["RDOC greeting", "hello", "world", "ENDRDOC"]) == {
        "greeting": "hello\nworld"
    }
    with verification_step("No markers → no snippets"):
        assert collect_from_lines(["just some prose", "no markers here"]) == {}


@allure.story("DI-17")
@allure.label("output", "rdm/evidence/translate.py")
def test_translates_foreign_test_results(tmp_path: Path) -> None:
    """DI-17: a gtest XML translates into RDM result data; unknown format rejected."""
    out = tmp_path / "results.yml"
    translate_test_results("gtest", str(_GTEST_XML), str(out))
    results = load_yaml(str(out))
    assert results["SomeModule.Cherry"]["result"] == "pass"
    assert results["HasOneFailure.BadOne"]["result"] == "fail"

    with verification_step("A different format (qttest) is also supported, not just gtest"):
        qt_out = tmp_path / "qt.yml"
        translate_test_results("qttest", str(_QTTEST_XML), str(qt_out))
        qt = load_yaml(str(qt_out))
        assert qt["some_module.SomeName::someTestCase"]["result"] == "pass"

    with verification_step("an errored or skipped xunit case is not a pass; a case is read once, the worse kept"):
        xunit = tmp_path / "xunit.xml"
        xunit.write_text(
            '<testsuites><testsuite name="S">'
            '<testcase name="errored"><error message="boom"/></testcase>'
            '<testcase name="skipped"><skipped/></testcase>'
            '<testcase name="twice"><failure message="first"/></testcase><testcase name="twice"/>'
            '<testsuite name="Inner"><testcase name="nested"/></testsuite>'
            '</testsuite></testsuites>')
        translate_test_results("xunit", str(xunit), str(out))
        assert {k: v["result"] for k, v in load_yaml(str(out)).items()} == {
            "S.errored": "fail", "S.skipped": "skip", "S.twice": "fail", "Inner.nested": "pass"}
    with verification_step("a qttest function fails when any incident does, whatever comes after"):
        qt = tmp_path / "qt2.xml"
        qt.write_text('<TestCase name="C"><Environment/><TestFunction name="f">'
                      '<Incident type="fail"><Description>row 1</Description></Incident>'
                      '<Incident type="pass"/></TestFunction></TestCase>')
        translate_test_results("auto", str(qt), str(out))
        assert load_yaml(str(out)) == {"C.f": {"name": "C.f", "result": "fail", "message": "row 1"}}
    with verification_step("An unknown format is rejected"):
        with pytest.raises(ValueError):
            translate_test_results("nonsense-format", str(_GTEST_XML), str(out))
