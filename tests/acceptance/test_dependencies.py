"""Acceptance test for selective invalidation via ``depends_on`` (DI-35, see dhf/).

The acceptance criterion ("live BDD"), tagged `@allure.story("DI-35")`, over
the real frontmatter reader, `faithfulness.reconcile`, and the design gate.
Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path

import pytest

from rdm.record import faithfulness as f
from rdm.record.sdd import design_inputs
from rdm.story_audit.design_gate import run_design_gate

allure = pytest.importorskip("allure")

_ENTRIES = {
    # DI-3 -> DI-2 -> DI-1 (transitive); DI-4 unrelated; DI-5 <-> DI-6 cycle,
    # with DI-8 hanging off it (depends on the cycle without being part of it)
    # and DI-9 -> DI-10 -> DI-11 -> DI-9 a 3-cycle; DI-7 names an undeclared input.
    "DI-1": ("Base requirement.", []),
    "DI-2": ("Builds on the base.", ["DI-1"]),
    "DI-3": ("Builds on the builder.", ["DI-2"]),
    "DI-4": ("Unrelated requirement.", []),
    "DI-5": ("Half of a cycle.", ["DI-6"]),
    "DI-6": ("Other half of a cycle.", ["DI-5"]),
    "DI-7": ("Depends on nothing declared.", ["DI-99"]),
    "DI-8": ("Depends on a cycle it is not part of.", ["DI-5"]),
    "DI-9": ("First of a three-cycle.", ["DI-10"]),
    "DI-10": ("Second of a three-cycle.", ["DI-11"]),
    "DI-11": ("Third of a three-cycle.", ["DI-9"]),
}


def _within(seconds: float, fn):
    """Run ``fn`` and fail (instead of hanging the suite) if it does not return."""
    box: dict = {}
    worker = threading.Thread(target=lambda: box.setdefault("value", fn()), daemon=True)
    worker.start()
    worker.join(seconds)
    assert not worker.is_alive(), "dependency resolution did not terminate (cycle not tolerated)"
    return box["value"]


def _write_dhf(dhf: Path, texts: dict[str, str]) -> None:
    lines = ["---", "kind: design", "context: core", "satisfies: [UN-1]", "design_inputs:"]
    for di_id, (_, deps) in _ENTRIES.items():
        lines += [f"  - id: {di_id}", f'    text: "{texts[di_id]}"', "    traces_to: [UN-1]"]
        if deps:
            lines.append(f"    depends_on: [{', '.join(deps)}]")
    lines += ["---", "", "# Core"]
    docs = dhf / "documents" / "design"
    docs.mkdir(parents=True, exist_ok=True)
    (docs / "core.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _pin_all(dhf: Path, vdir: Path, scope: str) -> None:
    inputs = design_inputs(dhf)
    hashes = f.current_hashes(inputs, None, scope)
    vdir.mkdir(parents=True, exist_ok=True)
    for di_id, digest in hashes.items():
        body = {"design_input": di_id, "verdict": "faithful", "test_hash": digest, "hash_scope": scope}
        (vdir / f"{di_id}-faithfulness.json").write_text(json.dumps(body))


def _stale_after_rewording(tmp_path: Path, reworded: str, scope: str = f.SCOPE_FUNCTION) -> list[str]:
    dhf = tmp_path / scope / reworded / "dhf"
    texts = {di: text for di, (text, _) in _ENTRIES.items()}
    _write_dhf(dhf, texts)
    vdir = tmp_path / scope / reworded / "verdicts"
    _pin_all(dhf, vdir, scope)
    assert f.reconcile(design_inputs(dhf), vdir, None).stale == []  # all current first
    texts[reworded] = texts[reworded] + " Reworded."
    _write_dhf(dhf, texts)
    return f.reconcile(design_inputs(dhf), vdir, None).stale


@allure.story("DI-35")
@allure.label("output", "rdm/record/faithfulness.py")
def test_upstream_change_invalidates_only_downstream(tmp_path: Path) -> None:
    """DI-35: depends_on is declared in frontmatter; rewording an upstream input
    stales its transitive dependents and nothing else, cycles terminate, and an
    undeclared dependency is a design-gate warning."""
    # Clause: a design input declares the inputs it depends_on.
    dhf = tmp_path / "declared" / "dhf"
    _write_dhf(dhf, {di: text for di, (text, _) in _ENTRIES.items()})
    declared = {di["id"]: di["depends_on"] for di in design_inputs(dhf)}  # parse only: no resolution
    assert declared["DI-2"] == ["DI-1"] and declared["DI-4"] == []

    # Clauses: the pin covers the transitive upstream text -> rewording DI-1
    # stales DI-1 itself and its downstream DI-2 and DI-3 ...
    stale = _within(5, lambda: _stale_after_rewording(tmp_path, "DI-1"))
    assert {"DI-2", "DI-3"} <= set(stale)
    # ... while unrelated inputs stay current.
    assert stale == ["DI-1", "DI-2", "DI-3"]
    # The same holds for module-scope pins (the default verdict scope).
    assert _within(5, lambda: _stale_after_rewording(tmp_path, "DI-1", f.SCOPE_MODULE)) == ["DI-1", "DI-2", "DI-3"]
    # A downstream change does not travel upstream.
    assert _within(5, lambda: _stale_after_rewording(tmp_path, "DI-3")) == ["DI-3"]

    # Clause: dependency cycles are tolerated -- resolution terminates, every
    # member of a cycle reacts, and so does an input depending on the cycle.
    assert _within(5, lambda: _stale_after_rewording(tmp_path, "DI-5")) == ["DI-5", "DI-6", "DI-8"]
    assert _within(5, lambda: _stale_after_rewording(tmp_path, "DI-10")) == ["DI-10", "DI-11", "DI-9"]
    assert _within(5, lambda: _stale_after_rewording(tmp_path, "DI-8")) == ["DI-8"]

    # Clause: the design gate warns on a dependency naming an undeclared input.
    warnings = _within(5, lambda: run_design_gate(dhf).task_warnings)
    assert "design input DI-7 depends_on unknown design input DI-99" in warnings
    assert not any("DI-2 depends_on" in w for w in warnings)
