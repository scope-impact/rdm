"""Acceptance test for the risk register in the graph (DI-45, see dhf/).

Tagged `@allure.story("DI-45")`, over the real projection, the shipped
shapes, the real release gate (for agreement) and the agent's trace. Skips
cleanly if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import clause  # noqa: E402
pytest.importorskip("pyshacl")

from rdm.graph.agent import Record, trace  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate  # noqa: E402
from tests.acceptance.test_graph_shapes import _dhf, _results  # noqa: E402
from tests.acceptance.test_risk import ACCEPT, CASES, _gate, _policy, _register, _risk  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"


def _decision(quads, risk_id: str) -> str:
    return next(q.object.value for q in quads if q.subject.value.endswith(f"risk/{risk_id}")
                and q.predicate.value == RDM + "residualDecision")


@allure.story("DI-45")
@allure.label("output", "rdm/graph/project.py")
def test_risks_in_the_graph_agree_with_the_release_gate(tmp_path: Path) -> None:
    """DI-45: each risk with its branch, links, chain, scores, levels,
    residual decision, status, controls and acceptance in a risks graph;
    shapes blocking exactly the risks the release gate blocks; trace by risk
    id, and a design input's trace listing the risks it controls."""
    dhf = _dhf(tmp_path / "facts")
    _policy(dhf)
    _register(dhf, [
        _risk("RISK-F-1", category="security", stride="Spoofing", linked=["RISK-F-2"], hazard="Hz", situation="Si",
              harm="Ha", level="High", controls=["DI-1", "DI-2"], residual={"probability": "Unlikely"},
              acceptance=ACCEPT, status="proposed"),
        _risk("RISK-F-2", residual={"severity": "Minor", "probability": "Rare"}),
    ])
    results = _results(tmp_path / "facts", {"DI-1": ["passed"], "DI-2": ["passed"]})
    quads = project(dhf, results)
    risk = "urn:dhf:proj:risk/RISK-F-1"
    facts = {(q.predicate.value.replace(RDM, ""), q.object.value) for q in quads if q.subject.value == risk}
    assert {q.graph_name.value for q in quads if q.subject.value == risk} == {"urn:dhf:proj:graph/risks"}
    for fact in (("category", "security"), ("stride", "Spoofing"), ("linkedTo", "urn:dhf:proj:risk/RISK-F-2"),
                 ("hazard", "Hz"), ("situation", "Si"), ("harm", "Ha"), ("severity", "Serious"),
                 ("probability", "Possible"), ("level", "High"), ("recordedLevel", "High"),
                 ("residualProbability", "Unlikely"), ("residualLevel", "Medium"), ("residualDecision", "accepted"),
                 ("acceptedBy", "QA lead"), ("acceptanceRationale", "ALARP"), ("riskStatus", "proposed"),
                 ("controlledBy", "urn:dhf:proj:input/DI-1"), ("controlledBy", "urn:dhf:proj:input/DI-2"),
                 ("declaredIn", "urn:dhf:proj:doc/RMF-risks"),
                 ("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", RDM + "Risk")):
        assert fact in facts, fact
    f2 = {(q.predicate.value.replace(RDM, ""), q.object.value) for q in quads
          if q.subject.value == "urn:dhf:proj:risk/RISK-F-2"}
    assert {("residualSeverity", "Minor"), ("residualLevel", "Low"), ("residualDecision", "acceptable")} <= f2
    report = validate(quads)
    assert not [r for r in report if r.severity == "Violation"]
    risk_warnings = [r for r in report if r.severity == "Warning" and not r.message.startswith("test run")]
    assert [(r.label, r.message) for r in risk_warnings] == [
        ("RISK-F-1", "risk is proposed: a person has not approved its rating")]

    with clause("The residual decision is \"not evaluated\" until every control has a passing test"):
        (tmp_path / "facts-red").mkdir()
        assert _decision(project(dhf, _results(tmp_path / "facts-red", {"DI-1": ["passed"], "DI-2": ["failed"]})),
                         "RISK-F-1") == "not evaluated"
        assert _decision(project(dhf), "RISK-F-1") == "not evaluated"   # no results at all

    # Trace: a risk by id (case-insensitive) with each controlling input, and
    # a design input with the risks it controls.
    with clause("Trace: a risk by id (case-insensitive) with each controlling input, and a design input with the…"):
        record = Record(dhf, results)
        traced = trace(record, "risk-f-1")["risk"]
        assert (traced["id"], traced["category"], traced["stride"], traced["linked"]) == (
            "RISK-F-1", "security", "Spoofing", ["RISK-F-2"])
        assert (traced["level"], traced["residualLevel"], traced["residualDecision"], traced["riskStatus"]) == (
            "High", "Medium", "accepted", "proposed")
        assert [c["id"] for c in traced["controls"]] == ["DI-1", "DI-2"] and traced["undeclared_controls"] == []
        assert traced["controls"][0]["runs"] == [{"test": "DI-1-0", "status": "passed", "steps": [], "attachments": []}]
        assert trace(record, "DI-2")["design_input"]["risks"] == ["RISK-F-1"]
        with pytest.raises(ValueError, match="RISK-NOPE-1 is not a declared"):
            trace(record, "RISK-NOPE-1")

    with clause("The shapes block exactly the risks the release gate blocks, case by case"):
        for name, (entries, with_policy, _) in CASES.items():
            case_dhf, gate = _gate(tmp_path / "agree", name, entries, with_policy)
            results = tmp_path / "agree" / name / "allure"
            by_gate = {m for msg in gate.blocking for m in re.findall(r"^risk (RISK-[A-Z]+-\d+)", msg)}
            by_shapes = {r.label for r in validate(project(case_dhf, results))
                         if r.severity == "Violation" and r.focus.startswith("urn:dhf:proj:risk/")}
            assert by_shapes == by_gate, (name, by_shapes, by_gate)
    with clause("A risk with no id: the gate blocks it, and so do the shapes, on a stand-in node"):
        case_dhf, gate = _gate(tmp_path / "agree", "no-id", [_risk(None), _risk("RISK-N-1")])
        assert "a risk in dhf/documents/risk/risks.md has no id" in gate.blocking
        flagged = [r for r in validate(project(case_dhf, tmp_path / "agree" / "no-id" / "allure"))
                   if r.severity == "Violation" and r.message == "risk has no id"]
        assert [r.label for r in flagged] == ["risk with no id #1 in dhf/documents/risk/risks.md"]
        undeclared = trace(Record(tmp_path / "agree" / "undeclared-control" / "proj" / "dhf"), "RISK-U-2")["risk"]
        assert [c["id"] for c in undeclared["controls"]] == ["DI-1"] and undeclared["undeclared_controls"] == ["DI-77"]
