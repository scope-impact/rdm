"""CLI smoke tests: `rdm story journal|lock|gate-selftest` dispatch to the real
commands (the acceptance tests call the command functions directly)."""

from __future__ import annotations

from pathlib import Path

from rdm.main import cli
from rdm.record import anchor, journal
from rdm.story_audit.gate_selftest import build_fixture


def test_journal_and_lock_dispatch(tmp_path: Path, capsys) -> None:
    fx = build_fixture(tmp_path / "repo")
    assert cli(["story", "journal", "--dhf", str(fx.dhf), "--verify"]) == 0
    assert "Journal PASSED: 2 event(s)" in capsys.readouterr().out
    path = journal.journal_path(fx.dhf)
    path.write_text(path.read_text().replace('"faithful"', '"unfaithful"', 1))
    assert cli(["story", "journal", "--dhf", str(fx.dhf), "--verify"]) == 1

    anchor.lock_path(fx.dhf).unlink()
    assert cli(["story", "lock", "--dhf", str(fx.dhf)]) == 0
    assert set(anchor.read_lock(fx.dhf)["inputs"]) == {"DI-1", "DI-2"}


def test_gate_selftest_dispatch(capsys) -> None:
    assert cli(["story", "gate-selftest"]) == 0
    assert "recall: 10/10 fault(s) caught" in capsys.readouterr().out


def test_verdict_requires_reviewer_unless_carrying_forward(tmp_path: Path, capsys) -> None:
    fx = build_fixture(tmp_path / "repo")
    assert cli(["story", "verdict", "DI-1", "--dhf", str(fx.dhf), "--verdict", "faithful"]) == 2
    assert "--reviewer and --rationale are required" in capsys.readouterr().out
    # Nothing is stale, so carry-forward is refused.
    assert cli(["story", "verdict", "DI-1", "--dhf", str(fx.dhf), "--carry-forward"]) == 1
    assert "not stale" in capsys.readouterr().out
