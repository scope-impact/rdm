"""Acceptance test for stale-verdict change classification (DI-36, see dhf/).

The acceptance criterion ("live BDD"), tagged `@allure.story("DI-36")`, over
the real `record_verdict`, `faithfulness.reconcile`, the faithfulness report,
and `rdm story verdict --carry-forward`. Skips cleanly if allure-pytest is not
installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.record import faithfulness as f
from rdm.story_audit.design_gate import (
    carry_forward_verdict,
    run_faithfulness_gate,
    story_faithfulness_command,
    story_verdict_command,
)
from rdm.story_audit.gate_selftest import _TESTS, build_fixture

allure = pytest.importorskip("allure")

# Same behavior, different bytes: a comment, a docstring, re-spacing.
_COSMETIC = _TESTS.replace(
    "def test_double():\n    assert 2 * 2 == 4",
    'def test_double():\n    """Doubles."""\n    # two times two\n    assert 2*2   ==   4\n\n',
)
_BEHAVIORAL = _TESTS.replace("assert 2 * 2 == 4", "assert 2 * 2 >= 0")

# A non-Python verifying test (DI-31 polyglot discovery) for DI-2.
_JS = """const allure = require("allure-js-commons");

test("doubles", () => {
  allure.story("DI-2");
  const doubled = 2
    * 2;
  expect(doubled).toBe(4);
});
"""
_JS_COSMETIC = (
    "/**\n * Doubling suite.\n */\n// a line comment\n# shebang-style comment\n"
    + _JS.replace('test("doubles", () => {', 'test("doubles",   () => {\n\n')
)
# Behavioral edits on lines a naive comment filter would drop: a `*`
# continuation line and a JS `#private` field.
_JS_STAR_CHANGE = _JS.replace("    * 2;", "    * 3;")
# The private field on its own line: a line *starting* with `#` is code here.
_JS_PRIVATE = _JS.replace('test("doubles"', 'class K {\n#limit = 1;\n}\ntest("doubles"')
_JS_PRIVATE_CHANGE = _JS_PRIVATE.replace("#limit = 1;", "#limit = 2;")
# Code after a block comment closes, on a one-line and on a multi-line block.
_JS_INLINE_BLOCK = _JS.replace("  expect(doubled).toBe(4);", "  /* note */ expect(doubled).toBe(4);")
_JS_MULTI_BLOCK = _JS.replace("  expect(doubled).toBe(4);", "  /*\n   note\n  */ expect(doubled).toBe(4);")


def _verdict(fx) -> dict:
    return json.loads((fx.dhf / "faithfulness" / "DI-2-faithfulness.json").read_text())


def _reword(fx, old: str, new: str) -> None:
    fx.design_doc.write_text(fx.design_doc.read_text().replace(old, new))


@allure.story("DI-36")
@allure.label("output", "rdm/record/faithfulness.py")
def test_stale_verdicts_are_classified_and_only_class_a_carries_forward(tmp_path: Path, capsys) -> None:
    """DI-36: verdicts record normalized fingerprints; stale verdicts classify
    A/B/C/D in the report; carry-forward re-pins class A only, recording it."""
    # Clause: each new verdict records normalized text + test fingerprints.
    fx = build_fixture(tmp_path / "a")
    recorded = _verdict(fx)
    assert recorded["text_fingerprint"].startswith("sha256:")
    assert recorded["tests_fingerprint"].startswith("sha256:")

    # Clause: they ignore whitespace, comments, and docstrings -> class A.
    fx.tests_file.write_text(_COSMETIC)
    _reword(fx, "double a number.", "double   a\n      number.")  # whitespace-only rewording
    report = run_faithfulness_gate(fx.dhf)
    # Module-scope pins: both inputs' verdicts cover the edited file.
    assert report.stale == ["DI-1", "DI-2"]
    assert {report.by_id[d].change_class for d in report.stale} == {f.CLASS_TRIVIAL}

    # Clause: the class is shown in the faithfulness report.
    capsys.readouterr()
    story_faithfulness_command(fx.dhf)
    out = capsys.readouterr().out
    assert "DI-2: stale (class A: formatting/comment-only change)" in out

    # Clause: carry-forward re-pins class A without review, recording it.
    old_hash = recorded["test_hash"]
    path, reason = carry_forward_verdict(fx.dhf, "DI-2")
    assert path is not None and reason == ""
    carried = _verdict(fx)
    assert carried["carried_forward"] == {"class": "A", "from_hash": old_hash}
    assert carried["verdict"] == recorded["verdict"] and carried["reviewer"] == recorded["reviewer"]
    assert run_faithfulness_gate(fx.dhf).by_id["DI-2"].status == f.FAITHFUL

    # Clause: class B -- the verifying test's behavior changed.
    fx = build_fixture(tmp_path / "b")
    fx.tests_file.write_text(_BEHAVIORAL)
    assert run_faithfulness_gate(fx.dhf).by_id["DI-2"].change_class == f.CLASS_TEST
    b_before = _verdict(fx)
    # Clause: carry-forward refuses it (CLI exits non-zero, verdict untouched).
    assert story_verdict_command("DI-2", None, None, None, dhf_dir=fx.dhf, carry_forward=True) == 1
    assert "carry-forward refused" in capsys.readouterr().out
    assert _verdict(fx) == b_before

    # Clause: class C -- the requirement text changed (dominates a test change).
    fx = build_fixture(tmp_path / "c")
    _reword(fx, "double a number.", "triple a number.")
    fx.tests_file.write_text(_BEHAVIORAL)
    report = run_faithfulness_gate(fx.dhf)
    assert report.by_id["DI-2"].change_class == f.CLASS_REQUIREMENT
    assert carry_forward_verdict(fx.dhf, "DI-2")[0] is None

    # Clause (non-Python sources): comments and whitespace are ignored there
    # too, but code on comment-looking lines is not.
    def js_class(name: str, first: str, second: str) -> str:
        fx = build_fixture(tmp_path / name)
        js = fx.repo / "tests" / "calc.test.js"
        js.write_text(first)
        from rdm.story_audit.design_gate import record_verdict
        record_verdict(fx.dhf, "DI-2", "faithful", reviewer="r", rationale="with the JS test")
        assert run_faithfulness_gate(fx.dhf).by_id["DI-2"].status == f.FAITHFUL
        js.write_text(second)
        return run_faithfulness_gate(fx.dhf).by_id["DI-2"].change_class

    assert js_class("js-a", _JS, _JS_COSMETIC) == f.CLASS_TRIVIAL
    assert js_class("js-b", _JS, _JS.replace("toBe(4)", "toBe(5)")) == f.CLASS_TEST
    assert js_class("js-star", _JS, _JS_STAR_CHANGE) == f.CLASS_TEST
    assert js_class("js-private", _JS_PRIVATE, _JS_PRIVATE_CHANGE) == f.CLASS_TEST
    assert js_class("js-inline", _JS_INLINE_BLOCK, _JS_INLINE_BLOCK.replace("toBe(4)", "toBe(5)")) == f.CLASS_TEST
    assert js_class("js-multi", _JS_MULTI_BLOCK, _JS_MULTI_BLOCK.replace("toBe(4)", "toBe(5)")) == f.CLASS_TEST
    # ... while the comments themselves remain cosmetic.
    assert js_class("js-inline-a", _JS_INLINE_BLOCK, _JS_INLINE_BLOCK.replace("note", "remark")) == f.CLASS_TRIVIAL

    # Clause (with DI-35): a dependent's text fingerprint covers its upstream
    # text, so rewording only the upstream input classifies the dependent as
    # C -- never A -- and its carry-forward is refused.
    fx = build_fixture(tmp_path / "upstream")
    _reword(fx, '    text: "The system shall double a number."\n    traces_to: [UN-1]\n',
            '    text: "The system shall double a number."\n    traces_to: [UN-1]\n    depends_on: [DI-1]\n')
    from rdm.story_audit.design_gate import record_verdict
    record_verdict(fx.dhf, "DI-2", "faithful", reviewer="r", rationale="now depends on DI-1")
    assert run_faithfulness_gate(fx.dhf).by_id["DI-2"].status == f.FAITHFUL
    _reword(fx, "add two numbers.", "add three numbers.")
    assert run_faithfulness_gate(fx.dhf).by_id["DI-2"].change_class == f.CLASS_REQUIREMENT
    assert carry_forward_verdict(fx.dhf, "DI-2")[0] is None

    # Clause: class D -- a verdict with no fingerprints on record.
    fx = build_fixture(tmp_path / "d")
    legacy = _verdict(fx)
    del legacy["text_fingerprint"], legacy["tests_fingerprint"]
    legacy["test_hash"] = "sha256:not-current"
    (fx.dhf / "faithfulness" / "DI-2-faithfulness.json").write_text(json.dumps(legacy))
    assert run_faithfulness_gate(fx.dhf).by_id["DI-2"].change_class == f.CLASS_UNKNOWN
    assert carry_forward_verdict(fx.dhf, "DI-2")[0] is None
