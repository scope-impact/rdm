"""Acceptance tests for Allure results as RDF (DI-54, DI-55, DI-56, see dhf/).

Tagged `@allure.story`, over the real projection and the agent's trace, from a
crafted Allure results directory: two executions of one test (one failed),
parameters, labels, links, and a container with before and after fixtures.
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
from tests.acceptance.evidence import attach, clause  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"
PROV = "http://www.w3.org/ns/prov#"
LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
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
    """DI-54: uuid, full name, times, status message and trace, parameters,
    every label as name and value, links; runs of one test share a test case."""
    dhf, results = _record(tmp_path)
    _results(results)
    quads = project(dhf, results)
    a, b = _facts(quads, P + "run/a-result"), _facts(quads, P + "run/b-result")
    attach("run a", sorted(a))
    with clause("identity, name and times"):
        assert {("http://purl.org/dc/terms/identifier", "u-a"), ("fullName", "tests.test_alarms#test_alarm"),
                ("prov:startedAtTime", "2026-09-21T14:13:20.000Z"),
                ("prov:endedAtTime", "2026-09-21T14:13:21.500Z")} <= a
    with clause("status message and trace of a failed run"):
        assert {("status", "failed"), ("statusMessage", "AssertionError: too quiet"),
                ("statusTrace", "Traceback ...\nAssertionError")} <= b
        assert not any(p.startswith("status") and p != "status" for p, _ in a)
    with clause("parameters, as name and value"):
        param = _facts(quads, P + "parameter/a-result/1")
        assert ("parameter", P + "parameter/a-result/1") in a
        assert {("name", "volume"), ("value", "80")} <= param
    with clause("every label, as name and value"):
        labels = {(dict(_facts(quads, o))["name"], dict(_facts(quads, o))["value"]) for p, o in a if p == "hasLabel"}
        assert labels == {("story", "DI-1"), ("output", "src/alarms.py"), ("suite", "alarms")}
    with clause("links that are IRIs, as rdfs:seeAlso"):
        assert ("http://www.w3.org/2000/01/rdf-schema#seeAlso", "https://tracker.example/ALM-7") in a
        assert not any(o == "not a url" for _, o in a)
    with clause("runs of one test share its test case"):
        case = P + "testcase/h-alarm"
        assert ("runOf", case) in a and ("runOf", case) in b
        assert (LABEL, "tests.test_alarms#test_alarm") in _facts(quads, case)


@allure.story("DI-55")
@allure.label("output", "rdm/graph/allure.py")
def test_container_fixtures_link_to_the_runs_they_served(tmp_path: Path) -> None:
    """DI-55: each before and after fixture, with name, status, times, steps and
    attachments, linked to the runs it set up or tore down."""
    dhf, results = _record(tmp_path)
    _results(results)
    quads = project(dhf, results)
    before, after = _facts(quads, P + "fixture/c-container/before/1"), _facts(quads, P + "fixture/c-container/after/1")
    attach("before fixture", sorted(before))
    with clause("a before fixture with its name, status, times, steps and attachments"):
        assert {("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", RDM + "Fixture"), (LABEL, "tmp_path"),
                ("phase", "before"), ("status", "passed"), ("prov:startedAtTime", "2026-09-21T14:13:19.000Z"),
                ("attachment", P + "attachment/s-attachment.txt"),
                ("step", P + "step/c-container/before/1/1")} <= before
    with clause("it sets up the runs it served, and only those that exist"):
        assert {o for p, o in before if p == "setsUp"} == {P + "run/a-result", P + "run/b-result"}
    with clause("an after fixture tears them down"):
        assert ("phase", "after") in after
        assert {o for p, o in after if p == "tearsDown"} == {P + "run/a-result", P + "run/b-result"}


@allure.story("DI-56")
@allure.label("output", "rdm/graph/allure.py")
def test_runs_link_to_the_code_they_exercise(tmp_path: Path) -> None:
    """DI-56: each run to the source files its output labels name; trace lists
    a design input's source files."""
    dhf, results = _record(tmp_path)
    _results(results)
    quads = project(dhf, results)
    with clause("a run links to each source file its output labels name"):
        assert {o for p, o in _facts(quads, P + "run/b-result") if p == "exercisesOutput"} == {
            P + "source/src/alarms.py", P + "source/src/speaker.py"}
        assert {("path", "src/alarms.py"), ("http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                                            RDM + "SourceFile")} <= _facts(quads, P + "source/src/alarms.py")
    with clause("trace lists the design input's source files"):
        traced = trace(Record(dhf, results), "DI-1")["design_input"]
        attach("trace DI-1", traced)
        assert traced["code"] == ["src/alarms.py", "src/speaker.py"]
