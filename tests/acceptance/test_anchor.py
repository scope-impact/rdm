"""Acceptance test for design-input content anchors and the lock (DI-37, see dhf/).

The acceptance criterion ("live BDD"), tagged `@allure.story("DI-37")`, over
the real `anchor` module, `rdm story lock`, the verification data, the trace,
and the design gate. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from rdm.record import anchor
from rdm.record.verify import build_verification
from rdm.story_audit.design_gate import build_trace, run_design_gate
from rdm.story_audit.gate_selftest import _DESIGN_DOC, build_fixture

allure = pytest.importorskip("allure")


def _lock(fx) -> dict:
    return json.loads(anchor.lock_path(fx.dhf).read_text())


@allure.story("DI-37")
@allure.label("output", "rdm/record/anchor.py")
def test_content_fingerprint_and_lock_guard_wording_and_ids(tmp_path: Path) -> None:
    """DI-37: fingerprints in verification data + trace; the lock records ids
    and retires removed ones; the design gate fails on rewording or id reuse
    and warns on unlocked ids."""
    # Clause: a short SHA-256 of the whitespace-normalized text.
    text = "The system shall add two numbers."
    expected = "sha256:" + hashlib.sha256(text.encode()).hexdigest()[:12]
    assert anchor.fingerprint(text) == expected
    assert anchor.fingerprint("  The system  shall add\n two numbers. ") == expected
    assert anchor.fingerprint("The system shall add three numbers.") != expected

    fx = build_fixture(tmp_path / "repo")
    # Clause: reported in the verification data and the trace.
    rows = {r["design_input"]: r for g in build_verification(fx.dhf, fx.results)["groups"]
            for r in g["design_inputs"]}
    assert rows["DI-1"]["fingerprint"] == expected
    assert build_trace(fx.dhf, "DI-1")["fingerprint"] == expected

    # Clause: `rdm story lock` records every declared id's fingerprint.
    assert anchor.lock_command(fx.dhf) == 0
    assert _lock(fx) == {
        "inputs": {"DI-1": expected, "DI-2": anchor.fingerprint("The system shall double a number.")},
        "retired": {},
    }
    assert run_design_gate(fx.dhf).passed

    # Clause: the design gate FAILS on a reworded input not re-locked ...
    fx.design_doc.write_text(_DESIGN_DOC.replace("add two numbers", "add two or more numbers"))
    fx.commit("reword DI-1")
    gate = run_design_gate(fx.dhf)
    assert not gate.passed
    reasons = [r for a in gate.artifacts if not a.ok for r in a.reasons]
    assert any("DI-1 was reworded since it was locked" in r for r in reasons), reasons
    # ... and re-locking (the visible acknowledgment) clears it.
    anchor.lock_command(fx.dhf)
    assert run_design_gate(fx.dhf).passed

    # Clause: removed ids are retained as retired by the lock.
    without_di2 = _DESIGN_DOC.replace("add two numbers", "add two or more numbers").split("  - id: DI-2")[0]
    fx.design_doc.write_text(without_di2 + "---\n\n# Core\n")
    fx.commit("drop DI-2")
    anchor.lock_command(fx.dhf)
    assert "DI-2" in _lock(fx)["retired"] and "DI-2" not in _lock(fx)["inputs"]

    # Clause: a retired id declared again FAILS the gate (re-locking cannot launder it).
    fx.design_doc.write_text(_DESIGN_DOC.replace("add two numbers", "add two or more numbers")
                             .replace("double a number", "halve a number"))
    fx.commit("reuse DI-2 for a different requirement")
    anchor.lock_command(fx.dhf)
    gate = run_design_gate(fx.dhf)
    assert not gate.passed
    assert any("DI-2 reuses a retired id" in r for a in gate.artifacts for r in a.reasons)

    # Clause: a declared id not yet locked is a WARNING (gate still passes).
    fx2 = build_fixture(tmp_path / "repo2")
    fx2.design_doc.write_text(_DESIGN_DOC.replace(
        "---\n\n# Core",
        '  - id: DI-3\n    text: "The system shall negate a number."\n    traces_to: [UN-1]\n---\n\n# Core',
    ))
    fx2.commit("declare DI-3")
    gate = run_design_gate(fx2.dhf)
    assert gate.passed
    assert any("DI-3 is not yet in design_inputs.lock.json" in w for w in gate.task_warnings)

    # Clause: the check applies only when the lock file is present.
    anchor.lock_path(fx2.dhf).unlink()
    fx2.commit("remove the lock")
    gate = run_design_gate(fx2.dhf)
    assert gate.passed and not any(a.name == "Design-input lock" for a in gate.artifacts)
