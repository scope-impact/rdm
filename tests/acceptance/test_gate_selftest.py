"""Acceptance test for the release-gate self-test (DI-38, see dhf/).

The acceptance criterion ("live BDD"), tagged `@allure.story("DI-38")`, over
the real `run_gate_selftest` and `rdm story gate-selftest`. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from rdm.story_audit import gate_selftest as gs

allure = pytest.importorskip("allure")

# The fault classes DI-38 names, in its words -> the selftest's fault names.
_REQUIRED_FAULTS = [
    "untested design input",
    "failing test",
    "unreviewed verdict",
    "unfaithful verdict",
    "partial verdict",
    "stale verdict",
    "user need no input traces to",
    "uncommitted design-document edit",
    "broken journal",
    "reworded locked design input",
]


@allure.story("DI-38")
@allure.label("output", "rdm/story_audit/gate_selftest.py")
def test_gate_selftest_proves_precision_and_recall(tmp_path: Path, capsys, monkeypatch) -> None:
    """DI-38: a clean synthetic DHF passes; each named fault, injected alone,
    is blocked; the command reports caught/missed and exits non-zero on a
    blocked baseline or an escaped fault."""
    # Clause: the synthetic DHF lives in a scratch git repository and passes.
    fx = gs.build_fixture(tmp_path / "probe")
    inside = subprocess.run(["git", "-C", str(fx.repo), "rev-parse", "--show-toplevel"],
                            capture_output=True, text=True, check=True)
    assert Path(inside.stdout.strip()).resolve() == fx.repo.resolve()
    assert gs._gate(fx) == []

    result = gs.run_gate_selftest()
    assert result.baseline_blocking == []
    # Clause: every fault class is injected in isolation and blocked by name.
    assert [o.name for o in result.faults] == _REQUIRED_FAULTS
    for outcome in result.faults:
        assert outcome.caught, (outcome.name, outcome.blocking)
    # Isolation: a single injected fault yields one kind of blocker, not a
    # cascade that would also "catch" the other faults.
    by_name = {o.name: o.blocking for o in result.faults}
    assert len(by_name["failing test"]) == 1 and len(by_name["partial verdict"]) == 1

    # Clause: the command reports caught/missed per fault and exits 0 when clean.
    capsys.readouterr()
    assert gs.gate_selftest_command() == 0
    out = capsys.readouterr().out
    for name in _REQUIRED_FAULTS:
        assert f"[CAUGHT] {name}" in out
    assert "[OK]     clean baseline passes" in out

    # Clause: an escaped fault -> reported MISSED, non-zero exit.
    monkeypatch.setattr(gs, "FAULTS", gs.FAULTS + [("no-op fault", lambda fx: None, "never named")])
    assert gs.gate_selftest_command() == 1
    out = capsys.readouterr().out
    assert "[MISSED] no-op fault" in out and "escaped: no-op fault" in out

    # Clause: a blocked clean baseline (false positive) -> non-zero exit.
    monkeypatch.setattr(gs, "FAULTS", [])
    monkeypatch.setattr(gs, "_DESIGN_REVIEW", gs._DESIGN_REVIEW + "\nTODO: finish\n")
    assert gs.gate_selftest_command() == 1
    assert "clean baseline was BLOCKED" in capsys.readouterr().out
