"""Acceptance test for negative knowledge across faithfulness reviews (DI-39, see dhf/).

The acceptance criterion ("live BDD"), tagged `@allure.story("DI-39")`, over
the real `record_verdict`, the `--stale` review worklist, and the release
gate. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.story_audit.design_gate import record_verdict, run_release_gate, story_faithfulness_command
from rdm.story_audit.gate_selftest import _TESTS, build_fixture

allure = pytest.importorskip("allure")

_SURVIVED = {"file": "calc.py", "find": "a * 2", "replace": "a + a", "test": "test_double", "result": "SURVIVED"}
_KILLED = {"file": "calc.py", "find": "a * 2", "replace": "a * 3", "test": "test_double", "result": "KILLED"}


def _prior(fx) -> list[dict]:
    return json.loads((fx.dhf / "faithfulness" / "DI-2-faithfulness.json").read_text())["prior_findings"]


@allure.story("DI-39")
@allure.label("output", "rdm/story_audit/design_gate.py")
def test_prior_findings_carry_forward_and_reach_the_worklist(tmp_path: Path, capsys) -> None:
    """DI-39: an earlier verdict's surviving probes + uncovered clauses (and
    what it carried) are preserved, named, shown in the --stale worklist, and
    never block release by themselves."""
    fx = build_fixture(tmp_path / "repo")
    record_verdict(fx.dhf, "DI-2", "partial", reviewer="alice", rationale="gap",
                   probes=[_SURVIVED, _KILLED], uncovered_clauses=["negative numbers"])

    # Clause: recording over it preserves its surviving probes + uncovered
    # clauses, naming the earlier verdict and reviewer (killing probes are not
    # findings).
    record_verdict(fx.dhf, "DI-2", "faithful", reviewer="bob", rationale="fixed")
    assert _prior(fx) == [{
        "verdict": "partial",
        "reviewer": "alice",
        "test_hash": _prior(fx)[0]["test_hash"],
        "surviving_probes": [_SURVIVED],
        "uncovered_clauses": ["negative numbers"],
    }]
    assert _prior(fx)[0]["test_hash"].startswith("sha256:")

    # Clause: findings it had already carried travel too (accumulation), even
    # through a verdict that added none of its own.
    record_verdict(fx.dhf, "DI-2", "faithful", reviewer="carol", rationale="re-pin")
    assert [p["reviewer"] for p in _prior(fx)] == ["alice"]
    record_verdict(fx.dhf, "DI-2", "partial", reviewer="dave", rationale="new gap",
                   uncovered_clauses=["zero"])
    record_verdict(fx.dhf, "DI-2", "faithful", reviewer="erin", rationale="fixed again")
    assert [(p["reviewer"], p["verdict"]) for p in _prior(fx)] == [("alice", "partial"), ("dave", "partial")]

    # Clause: prior findings never block a release by themselves.
    assert run_release_gate(fx.dhf, fx.results).blocking == []

    # Clause: the --stale worklist prints each listed input's prior findings.
    fx.tests_file.write_text(_TESTS.replace("assert 2 * 2 == 4", "assert 2 * 2 > 3"))
    capsys.readouterr()
    story_faithfulness_command(fx.dhf, stale_only=True)
    out = capsys.readouterr().out
    assert "DI-2: stale" in out
    assert ("prior finding (partial verdict by alice): probe SURVIVED -- calc.py: 'a * 2' -> 'a + a'") in out
    assert "prior finding (partial verdict by alice): uncovered clause -- negative numbers" in out
    assert "prior finding (partial verdict by dave): uncovered clause -- zero" in out
    assert "a * 3" not in out  # the killing probe is not negative knowledge
