"""Acceptance tests for Allure results as RDF (DI-54, DI-56, see dhf/).

Tagged `@allure.story`, over the real projection and the agent's trace, from a
crafted Allure results directory: two executions of one test (one failed),
parameters, labels, links, and a container with before and after fixtures —
the last three of which the graph deliberately leaves out.
Skips cleanly if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
pytest.importorskip("pyoxigraph")

from rdm.graph.agent import Record, trace  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"
PROV = "http://www.w3.org/ns/prov#"
P = "urn:dhf:acme:"


def _labels(*pairs):
    return [{"name": n, "value": v} for n, v in pairs]


def _results(results: Path) -> None:
    for f in results.glob("*"):
        f.unlink()
    common = {"fullName": "tests.test_alarms#test_alarm", "historyId": "h-alarm"}
    (results / "a-result.json").write_text(json.dumps({**common,
        "uuid": "u-a", "name": "test_alarm", "status": "passed", "start": 1790000000000, "stop": 1790000001500,
        "labels": _labels(("story", "DI-1"), ("output", "src/alarms.py"), ("suite", "alarms")),
        "parameters": [{"name": "volume", "value": "80"}],
        "links": [{"type": "issue", "url": "https://tracker.example/ALM-7"}, {"type": "tms", "url": "not a url"}]}))
    (results / "b-result.json").write_text(json.dumps({**common,
        "uuid": "u-b", "name": "test_alarm", "status": "failed", "start": 1790000100000, "stop": 1790000100200,
        "statusDetails": {"message": "AssertionError: too quiet", "trace": "Traceback ...\nAssertionError"},
        "labels": _labels(("story", "DI-1"), ("output", "src/alarms.py"), ("output", "src/speaker.py"))}))
    (results / "c-container.json").write_text(json.dumps({
        "uuid": "c-1", "children": ["u-a", "u-b", "u-missing"],
        "befores": [{"name": "tmp_path", "status": "passed", "start": 1789999999000, "stop": 1789999999100,
                     "attachments": [{"name": "setup log", "source": "s-attachment.txt", "type": "text/plain"}],
                     "steps": [{"name": "make device", "status": "passed"}]}],
        "afters": [{"name": "tmp_path::0", "status": "passed"}]}))


def _facts(quads, node: str) -> set[tuple[str, str]]:
    return {(q.predicate.value.replace(RDM, "").replace(PROV, "prov:"), q.object.value)
            for q in quads if q.subject.value == node}


@allure.story("DI-54")
@allure.label("output", "rdm/graph/allure.py")
def test_results_are_projected_in_full(tmp_path: Path) -> None:
    """DI-54: uuid, full name, times, status message and trace, parameters;
    not labels as nodes, links, a test case per history id, or fixtures."""
    dhf, results = _record(tmp_path)
    _results(results)
    quads = project(dhf, results)
    a, b = _facts(quads, P + "run/a-result"), _facts(quads, P + "run/b-result")
    attach("run a", sorted(a))
    with verification_step("identity, name and times"):
        assert {("http://purl.org/dc/terms/identifier", "u-a"), ("fullName", "tests.test_alarms#test_alarm"),
                ("prov:startedAtTime", "2026-09-21T14:13:20.000Z"),
                ("prov:endedAtTime", "2026-09-21T14:13:21.500Z")} <= a
    with verification_step("status message and trace of a failed run"):
        assert {("status", "failed"), ("statusMessage", "AssertionError: too quiet"),
                ("statusTrace", "Traceback ...\nAssertionError")} <= b
        assert not any(p.startswith("status") and p != "status" for p, _ in a)
    with verification_step("parameters, as name and value"):
        param = _facts(quads, P + "parameter/a-result/1")
        assert ("parameter", P + "parameter/a-result/1") in a
        assert {("name", "volume"), ("value", "80")} <= param
    with verification_step("not projected: labels as nodes, links, a test case per history id, container fixtures"):
        predicates = {q.predicate.value for q in quads}
        nodes = {q.subject.value for q in quads}
        assert not predicates & {RDM + "hasLabel", RDM + "setsUp", RDM + "tearsDown",
                                 "http://www.w3.org/2000/01/rdf-schema#seeAlso"}
        assert not any(n.startswith((P + "label/", P + "testcase/", P + "fixture/")) for n in nodes)
        executions = [q for q in quads if q.graph_name.value.endswith("graph/executions")]
        assert not any("tracker.example" in q.object.value or q.object.value == "alarms" for q in executions)
        assert not any("c-container" in n or "s-attachment" in n for n in nodes)  # nothing from the container
    with verification_step("story and output labels still link the run to its design input and its code"):
        assert ("exercises", P + "input/DI-1") in a and ("exercisesOutput", P + "source/src/alarms.py") in a


@allure.story("DI-56")
@allure.label("output", "rdm/graph/allure.py")
@allure.label("output", "rdm/graph/c4.py")
@allure.label("output", "rdm/graph/shapes.ttl")
def test_runs_link_to_the_code_they_exercise(tmp_path: Path) -> None:
    """DI-56: RDM shall link each test run to the components it names: by a component label,
    the key of a component the C4 model declares, or by an output label, the component whose
    code holds the file it names; a component label naming no component of the model shall be
    a warning; and the agent server's trace shall list, for a design input, the source files
    its runs' output labels name."""
    dhf, results = _record(tmp_path)
    _results(results)
    quads = project(dhf, results)
    with verification_step("a run links to each source file its output labels name"):
        assert {o for p, o in _facts(quads, P + "run/b-result") if p == "exercisesOutput"} == {
            P + "source/src/alarms.py", P + "source/src/speaker.py"}
        assert {("path", "src/alarms.py"), ("http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                                            RDM + "SourceFile")} <= _facts(quads, P + "source/src/alarms.py")
    with verification_step("trace lists the design input's source files"):
        traced = trace(Record(dhf, results), "DI-1")["design_input"]
        attach("trace DI-1", traced)
        assert traced["code"] == ["src/alarms.py", "src/speaker.py"]
    from rdm.graph.validate import validate
    from tests.acceptance.test_graph_c4 import _named_run, _workspace

    _workspace(dhf.parent)
    _named_run(results, "keyed", "DI-2", "app", "pager")
    quads = project(dhf, results)
    named = {(q.subject.value.replace(P, ""), q.object.value.replace(P, ""))
             for q in quads if q.predicate.value == RDM + "namesComponent"}
    attach("named components", sorted(named))
    with verification_step("an output label names the component whose code holds its file"):
        assert {("run/b-result", "element/alarms"), ("run/b-result", "element/app")} <= named
    with verification_step("a component label names the component the model declares under its key"):
        assert {c for r, c in named if r == "run/keyed-result"} == {"element/app"}
    with verification_step("a component label naming no component of the model is a warning"):
        assert ("unknownComponent", "pager") in _facts(quads, P + "run/keyed-result")
        warned = {(r.severity, r.label, r.message) for r in validate(quads) if "component label" in r.message}
        assert warned == {("Warning", "keyed", "test run's component label pager names no component of the C4 model")}
