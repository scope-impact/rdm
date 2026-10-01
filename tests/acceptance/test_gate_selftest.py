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
# For each, every blocker the injected fault may produce: the fault itself
# plus its unavoidable consequences (deleting a verdict also orphans its
# journal event; rewording a locked input also stales its verdict; one test
# module pins both inputs). Anything else blocking means the injection was
# not isolated.
_ISOLATED_BLOCKERS = {
    "untested design input": ["design input DI-2 not verified by any passing Allure test"],
    "failing test": ["design input DI-2 FAILED verification"],
    "unreviewed verdict": ["design input DI-2 has no faithfulness review",
                           "journal fails verification at seq 2: DI-2-faithfulness.json"],
    "unfaithful verdict": ["design input DI-2 FAILED faithfulness review"],
    "partial verdict": ["design input DI-2 is only PARTIALLY verified"],
    "stale verdict": ["design input DI-1 faithfulness review is STALE",
                      "design input DI-2 faithfulness review is STALE"],
    "user need no input traces to": ["user need UN-2 is addressed by no design input"],
    "uncommitted design-document edit": ["design control not met -- Software Design Description (core): "
                                         "has uncommitted changes"],
    "broken journal": ["journal fails verification at seq 1: event_hash does not match"],
    "reworded locked design input": ["design control not met -- Design-input lock: design input DI-1 "
                                     "was reworded", "design input DI-1 faithfulness review is STALE"],
}
_REQUIRED_FAULTS = list(_ISOLATED_BLOCKERS)


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
    # Isolation: each injection produces exactly its own blockers -- every
    # allowed blocker fires, and nothing else blocks.
    for outcome in result.faults:
        allowed = _ISOLATED_BLOCKERS[outcome.name]
        assert len(outcome.blocking) == len(allowed), (outcome.name, outcome.blocking)
        for expected in allowed:
            assert sum(m.startswith(expected) for m in outcome.blocking) == 1, (outcome.name, expected)

    # Clause: the command reports caught/missed per fault and exits 0 when clean.
    capsys.readouterr()
    assert gs.gate_selftest_command() == 0
    out = capsys.readouterr().out
    for name in _REQUIRED_FAULTS:
        assert f"[CAUGHT] {name}" in out
    assert "[OK]     clean baseline passes" in out

    # Clause: an escaped fault -> reported MISSED, non-zero exit. Being
    # blocked for some *other* reason does not count as catching it.
    monkeypatch.setattr(gs, "FAULTS", gs.FAULTS + [
        ("no-op fault", lambda fx: None, "never named"),
        ("mislabelled fault", gs._untested, "a blocker the gate never prints"),
    ])
    assert gs.gate_selftest_command() == 1
    out = capsys.readouterr().out
    assert "[MISSED] no-op fault" in out and "[MISSED] mislabelled fault" in out
    assert "escaped: no-op fault, mislabelled fault" in out

    # Clause: a blocked clean baseline (false positive) -> non-zero exit.
    monkeypatch.setattr(gs, "FAULTS", [])
    monkeypatch.setattr(gs, "_DESIGN_REVIEW", gs._DESIGN_REVIEW + "\nTODO: finish\n")
    assert gs.gate_selftest_command() == 1
    assert "clean baseline was BLOCKED" in capsys.readouterr().out
