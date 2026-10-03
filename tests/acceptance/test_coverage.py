"""Acceptance test for a run's coverage as evidence of what it ran (DI-74, see dhf/).

Tagged `@allure.story("DI-74")`, over the real projection, gate shapes and agent
trace, from crafted Allure results whose runs attach LCOV and Cobertura XML.
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
from rdm.graph.validate import validate  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402
from tests.acceptance.test_graph_c4 import _select, _workspace  # noqa: E402


def _covered_run(results: Path, stem: str, story: str, name: str, content: str, kind: str = "text/plain") -> None:
    """A passed run of ``story`` attaching ``content`` as its coverage."""
    (results / f"{stem}-attachment").write_text(content)
    (results / f"{stem}-result.json").write_text(json.dumps({
        "fullName": "tests.test_alarms#test_alarm", "name": stem, "status": "passed",
        "labels": [{"name": "story", "value": story}],
        "attachments": [{"name": name, "source": f"{stem}-attachment", "type": kind}]}))


def _coverage(dhf: Path, results: Path) -> None:
    root = dhf.parent.resolve()
    _covered_run(results, "lcov", "DI-1", "coverage", (  # an absolute path, and one relative to the project
        f"TN:\nSF:{root}/src/alarms.py\nDA:1,1\nDA:3,0\nend_of_record\n"
        "SF:src/speaker.py\nDA:1,0\nDA:2,0\nend_of_record\n"))
    _covered_run(results, "cobertura", "DI-2", "coverage.xml", (
        f'<?xml version="1.0" ?>\n<coverage version="7"><sources><source>{root}/src</source></sources>'
        '<packages><package name="src"><classes>'
        '<class name="speaker.py" filename="speaker.py"><lines><line number="1" hits="2"/></lines></class>'
        '<class name="alarms.py" filename="alarms.py"><lines><line number="1" hits="0"/></lines></class>'
        '</classes></package></packages></coverage>\n'), "application/xml")
    _covered_run(results, "broken", "DI-2", "coverage.xml", "<coverage><class filename=", "application/xml")


@allure.story("DI-74")
@allure.label("output", "rdm/evidence/allure.py")
@allure.label("output", "rdm/graph/allure.py")
@allure.label("output", "rdm/graph/shapes.ttl")
def test_a_runs_coverage_links_it_to_the_components_it_ran(tmp_path: Path) -> None:
    """DI-74: RDM shall read the coverage a test run attaches to its Allure result, as LCOV or
    Cobertura XML, take each file with a line the run executed as covered by that run, and link
    the run to the components whose code holds those files, apart from the components it names
    or reaches; a coverage attachment it cannot read shall be reported, never taken as covering
    nothing."""
    dhf, results = _record(tmp_path)
    _workspace(dhf.parent)  # alarms: src/alarms.py; app: src/ (speaker.py); alarms -> app
    _coverage(dhf, results)
    quads = project(dhf, results, infer=True)
    covered = _select(quads, "SELECT ?r ?f ?c WHERE { ?r rdm:covers ?f . OPTIONAL { ?f rdm:inComponent ?c } }")
    attach("covered", sorted(covered))

    with verification_step("an LCOV attachment: each file with an executed line is covered, linked to its component"):
        assert {(f, c) for r, f, c in covered if r == "run/lcov-result"} == {("source/src/alarms.py", "element/alarms")}
    with verification_step("a Cobertura attachment: likewise, its paths read against its source root"):
        assert {(f, c) for r, f, c in covered if r == "run/cobertura-result"} == {
            ("source/src/speaker.py", "element/app")}
    with verification_step("a file whose lines were all unexecuted is not covered"):
        assert ("run/lcov-result", "source/src/speaker.py", "element/app") not in covered
        assert ("run/cobertura-result", "source/src/alarms.py", "element/alarms") not in covered
    with verification_step("covered components are kept apart from those the run names or reaches"):
        assert not _select(quads, "SELECT ?r ?c WHERE { ?r rdm:namesComponent|rdm:reaches ?c }")
        traced = trace(Record(dhf, results), "DI-2")["design_input"]
        attach("trace DI-2", traced)
        assert [c["component"] for c in traced["covered_components"]] == ["app"]
        assert traced["components"] == [] and traced["reached_components"] == []
    with verification_step("a coverage attachment that cannot be read is reported as a warning"):
        warned = {(r.severity, r.label, r.message) for r in validate(quads) if "coverage" in r.message}
        assert warned == {("Warning", "broken",
                           "test run's coverage attachment coverage.xml could not be read as LCOV or Cobertura XML")}
        assert not any(r == "run/broken-result" for r, _, _ in covered)
