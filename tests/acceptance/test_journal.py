"""Acceptance test for the hash-chained event journal (DI-34, see dhf/).

The acceptance criterion ("live BDD"), tagged `@allure.story("DI-34")`, over
the real `record_verdict` -> `journal.append_event` path, `verify_journal`,
`rdm story journal --verify`, and the release gate. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from rdm.record import journal
from rdm.story_audit.design_gate import record_verdict, run_release_gate
from rdm.story_audit.gate_selftest import build_fixture

allure = pytest.importorskip("allure")


def _lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def _write(path: Path, lines: list[str]) -> None:
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


@allure.story("DI-34")
@allure.label("output", "rdm/record/journal.py")
def test_journal_is_hash_chained_verifiable_and_gates_release(tmp_path: Path, capsys) -> None:
    """DI-34: every recorded verdict appends a hash-chained event; verification
    names the first broken seq for an edit/delete/insert/reorder; a broken
    journal blocks the release gate."""
    fx = build_fixture(tmp_path / "repo")  # records DI-1, DI-2 -> two events
    path = journal.journal_path(fx.dhf)
    assert path == fx.dhf / "journal.jsonl"
    before = len(_lines(path))
    record_verdict(fx.dhf, "DI-1", "faithful", reviewer="r", rationale="again")
    events = [json.loads(line) for line in _lines(path)]

    # Clause: one event appended per recorded verdict.
    assert before == 2 and len(events) == 3
    newest = events[-1]
    assert newest["type"] == "verdict" and newest["payload"]["design_input"] == "DI-1"
    verdict_file = fx.dhf / "faithfulness" / "DI-1-faithfulness.json"
    assert newest["payload"]["verdict_sha256"] == "sha256:" + hashlib.sha256(verdict_file.read_bytes()).hexdigest()

    # Clause: each event carries seq, timestamp, type, payload, prev_hash, and
    # its own SHA-256 over its body (which includes prev_hash).
    for index, event in enumerate(events):
        assert set(event) == {"seq", "timestamp", "type", "payload", "prev_hash", "event_hash"}
        assert event["seq"] == index + 1 and event["timestamp"]
        body = {k: v for k, v in event.items() if k != "event_hash"}
        canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        assert event["event_hash"] == "sha256:" + hashlib.sha256(canonical.encode()).hexdigest()
    assert events[0]["prev_hash"] == journal.GENESIS_HASH
    assert [e["prev_hash"] for e in events[1:]] == [e["event_hash"] for e in events[:-1]]

    intact = _lines(path)
    assert journal.verify_journal(path).ok

    # Clause: edited / deleted / inserted / reordered events are detected at
    # the first broken seq.
    edited = list(intact)
    tampered = json.loads(edited[1])
    tampered["payload"]["verdict"] = "unfaithful"
    edited[1] = json.dumps(tampered, sort_keys=True)
    cases = {
        "edited": (edited, 2),
        "deleted": ([intact[0], intact[2]], 2),
        "inserted": ([intact[0], intact[1], intact[1], intact[2]], 3),
        "reordered": ([intact[0], intact[2], intact[1]], 2),
    }
    for name, (lines, broken) in cases.items():
        _write(path, lines)
        check = journal.verify_journal(path)
        assert not check.ok, name
        assert check.broken_seq == broken, (name, check)
    # A cut-off tail is invisible to the chain alone; the newest verdict event
    # per input is cross-checked against the verdict file on disk.
    _write(path, intact[:2])
    assert journal.verify_journal(path).ok
    truncated = journal.verify_journal(path, fx.dhf / "faithfulness")
    assert not truncated.ok and truncated.broken_seq == 1

    # Clause: `rdm story journal --verify` reports it (exit + first broken seq).
    _write(path, intact)
    capsys.readouterr()
    assert journal.journal_command(fx.dhf, verify=True) == 0
    assert "Journal PASSED: 3 event(s)" in capsys.readouterr().out
    _write(path, edited)
    assert journal.journal_command(fx.dhf, verify=True) == 1
    assert "Journal FAILED at seq 2" in capsys.readouterr().out

    # Clause: the release gate blocks on a journal that fails verification
    # (and only then: the intact journal passes).
    _write(path, intact)
    assert run_release_gate(fx.dhf, fx.results).blocking == []
    _write(path, edited)
    blocking = run_release_gate(fx.dhf, fx.results).blocking
    assert any(m.startswith("journal fails verification at seq 2") for m in blocking), blocking
