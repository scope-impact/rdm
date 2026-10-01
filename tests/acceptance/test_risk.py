"""Acceptance tests for the risk context's design inputs (DI-43, DI-44, see dhf/).

Tagged `@allure.story`, over the real register reader and the real release
gate. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest
import yaml

from rdm.record.risk import DEFAULT_MATRIX, read_matrix, risks
from rdm.story_audit.design_gate import run_release_gate
from tests.acceptance.test_graph_shapes import _dhf, _results

allure = pytest.importorskip("allure")


def _register(dhf: Path, entries: list[dict], name: str = "risks", **front) -> Path:
    path = dhf / "documents" / "risk" / f"{name}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("---\n" + yaml.safe_dump({"id": f"RMF-{name}", "kind": "risk", "risks": entries, **front},
                                             sort_keys=False) + "---\n# Risks\n")
    return path


def _risk(rid: str, **overrides) -> dict:
    entry = {"id": rid, "hazard": "h", "situation": "s", "harm": "x",
             "severity": "Serious", "probability": "Possible", "controls": ["DI-1"],
             "residual": {"probability": "Rare"}}
    entry.update(overrides)
    return {k: v for k, v in entry.items() if v is not None}


# Each case: register entries, and the gate's expected risk findings (a set of
# exact messages, or one message containing the text). Shared with DI-45's test.
CASES = {
    "sound": ([_risk("RISK-OK-1"), _risk("RISK-OK-2", severity="Minor", probability="Rare", controls=None,
                                          residual=None),
               _risk("RISK-OK-3", residual={"probability": "Unlikely"},
                     acceptance={"by": "QA", "rationale": "ALARP"})], set()),
    "duplicate": ([_risk("RISK-D-1"), _risk("RISK-D-1")], {"risk RISK-D-1 is declared 2 times"}),
    "no-hazard": ([_risk("RISK-C-1", hazard="")], {"risk RISK-C-1 has no hazard"}),
    "no-situation": ([_risk("RISK-C-2", situation=None)], {"risk RISK-C-2 has no situation"}),
    "no-harm": ([_risk("RISK-C-3", harm=" ")], {"risk RISK-C-3 has no harm"}),
    "unknown-severity": ([_risk("RISK-S-1", severity="Awful")], "not in the risk matrix"),
    "unknown-probability": ([_risk("RISK-S-2", probability=None)], "not in the risk matrix"),
    "mis-scored": ([_risk("RISK-S-3", level="Low")], {"risk RISK-S-3 records level Low; the matrix says High"}),
    # An acceptance does not excuse a risk that nothing controls.
    "uncontrolled": ([_risk("RISK-U-1", controls=None, residual=None, probability="Unlikely",
                            acceptance={"by": "QA", "rationale": "r"})],
                     "is Medium and nothing controls it"),
    "undeclared-control": ([_risk("RISK-U-2", controls=["DI-1", "DI-77"])],
                           {"risk RISK-U-2 names control DI-77, which is not a declared design input"}),
    "no-residual": ([_risk("RISK-R-1", residual=None)], {"risk RISK-R-1 has controls but no residual score"}),
    "bad-residual": ([_risk("RISK-R-2", residual={"probability": "Never"})], "residual probability 'Never'"),
    "block-residual": ([_risk("RISK-R-3", severity="Critical", probability="Likely",
                              residual={"probability": "Likely"},
                              acceptance={"by": "QA", "rationale": "r"})],
                       {"risk RISK-R-3 has a residual level of Block"}),
    "unaccepted-medium": ([_risk("RISK-R-4", residual={"probability": "Unlikely"})],
                          "has a residual level of Medium with no acceptance"),
    "unaccepted-high": ([_risk("RISK-R-5", residual={"probability": "Possible"},
                               acceptance={"by": "QA"})], "has a residual level of High with no acceptance"),
    "uncontrolled-low-ok": ([_risk("RISK-L-1", severity="Negligible", probability="Likely", controls=None,
                                   residual=None)], set()),
}


@allure.story("DI-43")
@allure.label("output", "rdm/record/risk.py")
def test_register_is_read_and_scored_from_the_matrix(tmp_path: Path) -> None:
    """DI-43: every field of a risk is read from kind: risk frontmatter, the
    initial and residual level come from the matrix (residual severity carries
    over), the default four-by-four applies unless a risk_matrix is declared."""
    dhf = tmp_path / "dhf"
    _register(dhf, [
        {"id": "RISK-A-1", "hazard": "Hz", "situation": "Si", "harm": "Ha", "severity": "Serious",
         "probability": "Possible", "level": "High", "controls": ["DI-1", "DI-2"],
         "residual": {"probability": "Unlikely"}, "acceptance": {"by": "QA lead", "rationale": "ALARP"}},
        {"id": "RISK-A-2", "hazard": "Hz", "situation": "Si", "harm": "Ha", "severity": "Minor",
         "probability": "Rare"},
    ])
    (dhf / "documents" / "notes.md").write_text("---\nid: N-1\nrisks: [{id: RISK-NOT-1}]\n---\n")  # not kind: risk

    found = {r.id: r for r in risks(dhf)}
    assert set(found) == {"RISK-A-1", "RISK-A-2"}
    a = found["RISK-A-1"]
    assert (a.hazard, a.situation, a.harm, a.severity, a.probability) == ("Hz", "Si", "Ha", "Serious", "Possible")
    assert a.recorded_level == "High" and a.controls == ["DI-1", "DI-2"] and a.residual_probability == "Unlikely"
    assert (a.accepted_by, a.acceptance_rationale) == ("QA lead", "ALARP")
    assert a.document == "dhf/documents/risk/risks.md"
    # Default matrix: Serious × Possible = High; residual keeps severity: Serious × Unlikely = Medium.
    assert read_matrix(dhf) is DEFAULT_MATRIX
    assert (a.level, a.residual_level) == ("High", "Medium")
    # Nothing controls it: the residual is the initial level.
    assert (found["RISK-A-2"].level, found["RISK-A-2"].residual_level) == ("Low", "Low")
    # The default matrix is the risk-analysis skill's, cell by cell.
    expected = {"Critical": "Medium High High Block", "Serious": "Low Medium High High",
                "Minor": "Low Low Medium Medium", "Negligible": "Low Low Low Low"}
    for severity, row in expected.items():
        assert [DEFAULT_MATRIX.level(severity, p) for p in ("Rare", "Unlikely", "Possible", "Likely")] == row.split()

    # A declared matrix replaces the default, for every risk in the register.
    (dhf / "documents" / "plan.md").write_text("---\nid: RMP-1\n" + yaml.safe_dump({"risk_matrix": {
        "severities": ["Bad", "Mild"], "probabilities": ["Seldom", "Often"],
        "levels": {"Bad": ["High", "Block"], "Mild": ["Low", "Medium"]}}}) + "---\n")
    _register(dhf, [_risk("RISK-B-1", severity="Bad", probability="Often", residual={"probability": "Seldom"}),
                    _risk("RISK-B-2", severity="Serious", probability="Possible")])
    custom = {r.id: r for r in risks(dhf)}
    assert read_matrix(dhf).source == "documents/plan.md"
    assert (custom["RISK-B-1"].level, custom["RISK-B-1"].residual_level) == ("Block", "High")
    assert custom["RISK-B-2"].level is None  # Serious/Possible are not in this matrix

    # A malformed matrix is refused, naming where it is.
    for bad in ({"severities": ["Bad"], "probabilities": ["Often"], "levels": {"Bad": ["Severe"]}},
                {"severities": ["Bad"], "probabilities": ["Often", "Seldom"], "levels": {"Bad": ["Low"]}}):
        (dhf / "documents" / "plan.md").write_text("---\nid: RMP-1\n" + yaml.safe_dump({"risk_matrix": bad}) + "---\n")
        with pytest.raises(ValueError, match="documents/plan.md"):
            read_matrix(dhf)


@allure.story("DI-44")
@allure.label("output", "rdm/record/risk.py")
def test_release_gate_blocks_on_the_risk_rules(tmp_path: Path) -> None:
    """DI-44: duplicate ids, a broken chain, an unscorable or mis-scored risk,
    an uncontrolled risk above Low, an undeclared control, a missing residual,
    a Block residual, and an unaccepted Medium/High residual each block; a
    sound register does not."""
    for name, (entries, expected) in CASES.items():
        dhf = _dhf(tmp_path / name)
        _register(dhf, entries)
        results = _results(tmp_path / name, {"DI-1": ["passed"], "DI-2": ["passed"]})
        gate = run_release_gate(dhf, results)
        found = [m for m in gate.blocking if "risk" in m]
        if isinstance(expected, set):
            assert set(found) == expected, (name, found)
        else:
            assert len(found) == 1 and expected in found[0], (name, found)
        assert gate.passed == (not expected), name
        if expected:  # the message names the risk, for people and for the graph's agreement test
            assert re.search(r"RISK-[A-Z]+-\d", found[0]), name

    # A risk with no id is named by its document; a malformed matrix blocks with its reason.
    dhf = _dhf(tmp_path / "anonymous")
    _register(dhf, [_risk(None)])
    results = _results(tmp_path / "anonymous", {"DI-1": ["passed"], "DI-2": ["passed"]})
    assert "a risk in dhf/documents/risk/risks.md has no id" in run_release_gate(dhf, results).blocking
    (dhf / "documents" / "plan.md").write_text("---\nid: RMP\nrisk_matrix: [1, 2]\n---\n")
    assert any("risk_matrix in documents/plan.md" in m for m in run_release_gate(dhf, results).blocking)
