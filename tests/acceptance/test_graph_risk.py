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
pytest.importorskip("pyshacl")

from rdm.graph.agent import Record, trace  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate  # noqa: E402
from rdm.story_audit.design_gate import run_release_gate  # noqa: E402
from tests.acceptance.test_graph_shapes import _dhf, _results  # noqa: E402
from tests.acceptance.test_risk import CASES, _register, _risk  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"


@allure.story("DI-45")
@allure.label("output", "rdm/graph/project.py")
def test_risks_in_the_graph_agree_with_the_release_gate(tmp_path: Path) -> None:
    """DI-45: each risk with its chain, scores, computed levels, controlling
    design inputs and acceptance in a risks graph; shapes blocking exactly the
    risks the release gate blocks; trace by risk id, and a design input's
    trace listing the risks it controls."""
    dhf = _dhf(tmp_path / "facts")
    _register(dhf, [_risk("RISK-F-1", hazard="Hz", situation="Si", harm="Ha", level="High", controls=["DI-1", "DI-2"],
                          residual={"probability": "Unlikely"}, acceptance={"by": "QA lead", "rationale": "ALARP"})])
    results = _results(tmp_path / "facts", {"DI-1": ["passed"], "DI-2": ["passed"]})
    quads = project(dhf, results)
    risk = "urn:dhf:proj:risk/RISK-F-1"
    facts = {(q.predicate.value.replace(RDM, ""), q.object.value) for q in quads if q.subject.value == risk}
    graphs = {q.graph_name.value for q in quads if q.subject.value == risk}
    assert graphs == {"urn:dhf:proj:graph/risks"}
    for fact in (("hazard", "Hz"), ("situation", "Si"), ("harm", "Ha"), ("severity", "Serious"),
                 ("probability", "Possible"), ("level", "High"), ("recordedLevel", "High"),
                 ("residualProbability", "Unlikely"), ("residualLevel", "Medium"), ("acceptedBy", "QA lead"),
                 ("acceptanceRationale", "ALARP"), ("controlledBy", "urn:dhf:proj:input/DI-1"),
                 ("controlledBy", "urn:dhf:proj:input/DI-2"), ("declaredIn", "urn:dhf:proj:doc/RMF-risks"),
                 ("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", RDM + "Risk")):
        assert fact in facts, fact
    assert not [r for r in validate(quads) if r.severity == "Violation"]

    # Trace: a risk by id (case-insensitive) with each controlling input, and
    # a design input with the risks it controls.
    record = Record(dhf, results)
    traced = trace(record, "risk-f-1")["risk"]
    assert (traced["id"], traced["level"], traced["residualLevel"], traced["acceptedBy"]) == (
        "RISK-F-1", "High", "Medium", "QA lead")
    assert [c["id"] for c in traced["controls"]] == ["DI-1", "DI-2"] and traced["undeclared_controls"] == []
    assert traced["controls"][0]["runs"] == [{"test": "DI-1-0", "status": "passed"}]
    assert trace(record, "DI-2")["design_input"]["risks"] == ["RISK-F-1"]
    with pytest.raises(ValueError, match="RISK-NOPE-1 is not a declared"):
        trace(record, "RISK-NOPE-1")

    # The shapes block exactly the risks the release gate blocks, case by case.
    for name, (entries, _) in CASES.items():
        dhf = _dhf(tmp_path / name)
        _register(dhf, entries)
        results = _results(tmp_path / name, {"DI-1": ["passed"], "DI-2": ["passed"]})
        by_gate = {m for msg in run_release_gate(dhf, results).blocking for m in re.findall(r"RISK-[A-Z]+-\d+", msg)}
        by_shapes = {r.label for r in validate(project(dhf, results))
                     if r.severity == "Violation" and r.focus.startswith("urn:dhf:proj:risk/")}
        assert by_shapes == by_gate, name
    undeclared = trace(Record(tmp_path / "undeclared-control" / "proj" / "dhf"), "RISK-U-2")["risk"]
    assert [c["id"] for c in undeclared["controls"]] == ["DI-1"] and undeclared["undeclared_controls"] == ["DI-77"]
