"""Acceptance test for test evidence in the graph (DI-53, see dhf/).

Tagged `@allure.story("DI-53")`, over the real projection and the agent's
trace, from an Allure result with nested steps and attachments. Skips
cleanly if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402
pytest.importorskip("pyoxigraph")

from rdm.graph.agent import Record, trace  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"


def _att(name: str, source: str, kind: str) -> dict:
    return {"name": name, "source": source, "type": kind}


@allure.story("DI-53")
@allure.label("output", "rdm/graph/project.py")
def test_runs_carry_their_steps_and_attachments(tmp_path: Path) -> None:
    """DI-53: each run's steps (name, status, order, nested under the parent)
    and attachments (on the run or a step: name, media type, file) are in the
    graph, and trace lists them with the run."""
    dhf, results = _record(tmp_path)
    (results / "r1-result.json").write_text(json.dumps({
        "name": "test_alarm", "status": "passed", "labels": [{"name": "story", "value": "DI-1"}],
        "attachments": [_att("gate output", "out-attachment.txt", "text/plain")],
        "steps": [
            {"name": "step 1: alarms sound", "status": "passed",
             "attachments": [_att("waveform", "wave-attachment.png", "image/png")],
             "steps": [{"name": "volume above threshold", "status": "passed"}]},
            {"name": "step 2: alarms log", "status": "passed"},
        ]}))
    quads = project(dhf, results)

    def facts(node):
        return {(q.predicate.value.replace(RDM, ""), q.object.value) for q in quads if q.subject.value == node}

    run, step = "urn:dhf:acme:run/r1-result", "urn:dhf:acme:step/r1-result/"
    with verification_step("Steps, in order and nested, with name and status"):
        assert {("step", step + "1"), ("step", step + "2")} <= facts(run)
        label = "http://www.w3.org/2000/01/rdf-schema#label"
        assert {("position", "1"), ("status", "passed"), (label, "step 1: alarms sound"),
                ("step", step + "1.1")} <= facts(step + "1")
        assert ("position", "1.1") in facts(step + "1.1") and ("step", step + "1.1") not in facts(run)
    with verification_step("Attachments on the run and on a step: name, media type, file"):
        out, wave = "urn:dhf:acme:attachment/out-attachment.txt", "urn:dhf:acme:attachment/wave-attachment.png"
        assert ("attachment", out) in facts(run) and ("attachment", wave) in facts(step + "1")
        assert {("path", "wave-attachment.png"), ("http://purl.org/dc/terms/format", "image/png"),
                (label, "waveform")} <= facts(wave)

    with verification_step("trace lists them with the run, nested and ordered"):
        runs = trace(Record(dhf, results), "DI-1")["design_input"]["runs"]
        assert runs == [{
            "test": "test_alarm", "status": "passed",
            "attachments": [{"name": "gate output", "type": "text/plain", "file": "out-attachment.txt"}],
            "steps": [
                {"name": "step 1: alarms sound", "status": "passed",
                 "attachments": [{"name": "waveform", "type": "image/png", "file": "wave-attachment.png"}],
                 "steps": [{"name": "volume above threshold", "status": "passed", "steps": [], "attachments": []}]},
                {"name": "step 2: alarms log", "status": "passed", "steps": [], "attachments": []},
            ]}]
