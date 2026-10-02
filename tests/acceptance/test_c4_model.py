"""Acceptance test for reading the C4 model (DI-66, see dhf/).

Tagged `@allure.story("DI-66")`. The model is read from the architecture
workspace's JSON export, ``<dhf>/c4/workspace.json``, which has the shape
Structurizr's CLI writes: people and software systems, containers inside
systems, components inside containers, each with its tags, group and
properties, and each relationship on its source. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from rdm.record.c4 import read_model  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]


def _element(ident: str, alias: str, name: str, **fields) -> dict:
    return {"id": ident, "name": name, "properties": {"structurizr.dsl.identifier": alias, **fields.pop("props", {})},
            **fields}


WORKSPACE = {
    "name": "Infusion pump",
    "model": {
        "people": [_element("1", "nurse", "Nurse", description="Programs infusions", tags="Element,Person",
                            relationships=[{"id": "20", "sourceId": "1", "destinationId": "4",
                                            "description": "programs infusions on"},
                                           {"id": "21", "sourceId": "1", "destinationId": "2",
                                            "description": "programs infusions on", "linkedRelationshipId": "20"}])],
        "softwareSystems": [
            _element("2", "pump", "Pump software", description="Delivers the dose", tags="Element,Software System",
                     containers=[_element(
                         "3", "firmware", "Firmware", technology="C", description="Runs on the pump",
                         tags="Element,Container",
                         components=[
                             _element("4", "ui", "Keypad UI", technology="C", description="Takes the program",
                                      tags="Element,Component", group="programming", props={"code": "src/ui/"},
                                      relationships=[{"id": "22", "sourceId": "4", "destinationId": "5",
                                                      "description": "sends the dose to", "technology": "IPC"}]),
                             _element("5", "dosing", "Dose control", technology="C", description="Limits the rate",
                                      tags="Element,Component", group="delivery",
                                      props={"code": "src/dose/limits.c"})]),
                         _element("6", "log", "Event log", technology="SQLite", description="What happened",
                                  tags="Element,Container,Database")]),
            _element("7", "ehr", "Hospital EHR", description="Orders", tags="Element,Software System,External")],
    },
    "views": {"systemContextViews": [{"key": "C1"}], "componentViews": [{"key": "C3_programming"}]},
}


@allure.story("DI-66")
@allure.label("output", "rdm/record/c4.py")
def test_the_c4_model_is_read_from_the_architecture_workspace(tmp_path: Path) -> None:
    """DI-66: RDM reads the C4 model from the architecture workspace's export:
    every element with its identifier, name, technology, description and
    whether it is external; the element that contains it; each component's
    bounded context (its group); every relationship with its source,
    destination, description and technology; and each component's code."""
    dhf = tmp_path / "dhf"
    (dhf / "c4").mkdir(parents=True)
    (dhf / "c4" / "workspace.dsl").write_text("workspace {}\n")
    (dhf / "c4" / "workspace.json").write_text(json.dumps(WORKSPACE))
    model = read_model(dhf, root=tmp_path)
    elements = model.elements
    attach("model", {alias: vars(e) for alias, e in elements.items()})

    with verification_step("every element is read by its identifier, typed by where the workspace puts it"):
        assert {a: e.kind for a, e in elements.items()} == {
            "nurse": "person", "pump": "system", "firmware": "container", "ui": "component",
            "dosing": "component", "log": "container", "ehr": "system"}
    with verification_step("its name, technology and description"):
        ui = elements["ui"]
        assert (ui.name, ui.technology, ui.description) == ("Keypad UI", "C", "Takes the program")
    with verification_step("whether it is external, and a database's shape"):
        assert elements["ehr"].external and not elements["pump"].external
        assert elements["log"].shape == "db"
    with verification_step("the element that contains it"):
        assert (elements["ui"].parent, elements["firmware"].parent, elements["pump"].parent) == (
            "firmware", "pump", None)
    with verification_step("each component's bounded context is its group"):
        assert (elements["ui"].context, elements["dosing"].context) == ("programming", "delivery")
    with verification_step("each component's code, a directory or a file"):
        assert (elements["ui"].link, elements["dosing"].link) == ("src/ui/", "src/dose/limits.c")
    with verification_step("every declared relationship, with its description and technology, not the implied"):
        attach("relationships", [vars(r) for r in model.relationships])
        assert [(r.source, r.target, r.label, r.technology) for r in model.relationships] == [
            ("nurse", "ui", "programs infusions on", ""), ("ui", "dosing", "sends the dose to", "IPC")]
    with verification_step("each element and relationship names the workspace that declares it, and its views"):
        assert {e.document for e in elements.values()} == {"dhf/c4/workspace.dsl"}
        assert model.views == ["C3_programming", "C1"]
    with verification_step("a DHF with no workspace has an empty model"):
        assert read_model(tmp_path / "empty").elements == {}

    with verification_step("RDM's own architecture reads whole: every component in a context, with its code"):
        own = read_model(ROOT / "dhf")
        components = own.components
        attach("RDM components", sorted(f"{c.context}: {c.alias} -> {c.link}" for c in components))
        assert len(components) >= 30 and len(own.views) >= 12
        assert all(c.context and c.link for c in components)
