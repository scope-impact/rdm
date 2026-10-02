"""Acceptance tests for the graph context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
ox = pytest.importorskip("pyoxigraph")

from rdm.graph.project import project  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_graph import PREFIXES, _record  # noqa: E402
from tests.acceptance.test_graph_allure import RDM, P, _results  # noqa: E402

ROOT = Path(__file__).parents[2]
RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"


def _workspace(repo: Path) -> None:
    """An architecture workspace over the acme record: a nurse using the pump's
    firmware, whose ``app`` component (``src/``) holds the more specific
    ``alarms`` (``src/alarms.py``), which imports ``app``'s speaker."""
    def element(ident, alias, name, **fields):
        props = {"structurizr.dsl.identifier": alias, **fields.pop("props", {})}
        return {"id": ident, "name": name, "properties": props, **fields}

    model = {"model": {
        "people": [element("1", "nurse", "Nurse", tags="Element,Person", relationships=[
            {"id": "9", "sourceId": "1", "destinationId": "4", "description": "silences"}])],
        "softwareSystems": [
            element("2", "pump", "Pump", description="Delivers the dose", containers=[element(
                "3", "firmware", "Firmware", technology="Python", components=[
                    element("4", "alarms", "Alarm logic", technology="Python", description="Decides to alarm",
                            group="alarms", props={"code": "src/alarms.py"},
                            relationships=[{"id": "10", "sourceId": "4", "destinationId": "5",
                                            "description": "sounds", "technology": "call"}]),
                    element("5", "app", "Device app", group="ui", props={"code": "src/"})])]),
            element("6", "ehr", "Hospital EHR", tags="Element,Software System,External")]},
        "views": {"componentViews": [{"key": "C3_alarms"}]}}
    (repo / "dhf" / "c4").mkdir()
    (repo / "dhf" / "c4" / "workspace.dsl").write_text("workspace {}\n")
    (repo / "dhf" / "c4" / "workspace.json").write_text(json.dumps(model))
    (repo / "src").mkdir()
    (repo / "src" / "speaker.py").write_text("def beep():\n    pass\n")
    (repo / "src" / "alarms.py").write_text("from src.speaker import beep\n\nbeep()\n")


def _select(quads, query: str) -> set[tuple[str, ...]]:
    store = ox.Store()
    store.extend(quads)
    rows = store.query(PREFIXES + query, use_default_graph_as_union=True)
    return {tuple(term.value.replace(P, "") for term in row if term is not None) for row in rows}


@allure.story("DI-67")
@allure.label("output", "rdm/graph/c4.py")
def test_the_c4_model_is_projected_into_the_graph(tmp_path: Path) -> None:
    """DI-67: RDM shall project the C4 model into the graph: each element typed person,
    software system, container or component, with its name, technology, description and
    external flag; the element that contains it; the bounded context that owns each
    component; each relationship with its source, target, label and technology; each
    component's code; the component every projected source file belongs to (the longest
    matching code path); and, for Python, an import from one component's code into another's
    as a dependency between the two."""
    dhf, results = _record(tmp_path)
    _results(results)
    _workspace(dhf.parent)
    quads = project(dhf, results)
    graphs = {q.graph_name.value.rsplit("/", 1)[-1] for q in quads}

    with verification_step("each element is typed, with its name, technology, description and external flag"):
        elements = _select(quads, """SELECT ?e ?type ?name ?tech ?desc ?ext WHERE {
            GRAPH <urn:dhf:acme:graph/architecture> { ?e a ?type ; rdfs:label ?name ; rdm:external ?ext .
              OPTIONAL { ?e rdm:technology ?tech } OPTIONAL { ?e dcterms:description ?desc } } }""")
        attach("elements", sorted(elements))
        typed = {(e, t.replace(RDM, ""), n, x) for e, t, n, *_, x in elements}
        assert typed == {("element/nurse", "Person", "Nurse", "false"),
                         ("element/pump", "SoftwareSystem", "Pump", "false"),
                         ("element/firmware", "Container", "Firmware", "false"),
                         ("element/alarms", "Component", "Alarm logic", "false"),
                         ("element/app", "Component", "Device app", "false"),
                         ("element/ehr", "SoftwareSystem", "Hospital EHR", "true")}
        assert ("element/alarms", RDM + "Component", "Alarm logic", "Python", "Decides to alarm", "false") in elements
    with verification_step("the element that contains it, and each component's bounded context, the record's"):
        assert _select(quads, "SELECT ?e ?p WHERE { ?e rdm:containedIn ?p }") == {
            ("element/firmware", "element/pump"), ("element/alarms", "element/firmware"),
            ("element/app", "element/firmware")}
        assert _select(quads, "SELECT ?e ?c WHERE { ?e rdm:inContext ?c . ?c a rdm:BoundedContext }") == {
            ("element/alarms", "context/alarms"), ("element/app", "context/ui")}
    with verification_step("each relationship with its source, target, label and technology"):
        assert _select(quads, """SELECT ?s ?t ?label ?tech WHERE { ?r a rdm:Relationship ; rdm:source ?s ;
            rdm:target ?t ; dcterms:description ?label . OPTIONAL { ?r rdm:technology ?tech } }""") == {
            ("element/nurse", "element/alarms", "silences"), ("element/alarms", "element/app", "sounds", "call")}
    with verification_step("each component's code"):
        assert _select(quads, "SELECT ?e ?code WHERE { ?e rdm:code ?code }") == {
            ("element/alarms", "src/alarms.py"), ("element/app", "src/")}
    with verification_step("each source file a run exercises belongs to the longest matching component"):
        owners = _select(quads, "SELECT ?f ?c WHERE { ?run rdm:exercisesOutput ?f . ?f rdm:inComponent ?c }")
        attach("source files", sorted(owners))
        assert owners == {("source/src/alarms.py", "element/alarms"), ("source/src/speaker.py", "element/app")}
    with verification_step("a Python import between components' code is a dependency, in its own graph"):
        assert _select(quads, """SELECT ?s ?t ?by ?imp WHERE { GRAPH <urn:dhf:acme:graph/code> {
            ?s rdm:dependsOn ?t . ?d a rdm:Dependency ; rdm:source ?s ; rdm:target ?t ;
            rdm:importedBy ?by ; rdm:imports ?imp } }""") == {
            ("element/alarms", "element/app", "src/alarms.py", "src/speaker.py")}
        assert {"architecture", "code"} <= graphs
    with verification_step("the terms are in the vocabulary"):
        terms = {q.subject.value for q in quads if q.graph_name.value.endswith("graph/ontology")}
        used = {q.predicate.value for q in quads if q.graph_name.value.rsplit("/", 1)[-1] in ("architecture", "code")}
        assert used - {RDF_TYPE, RDFS_LABEL} <= terms | {"http://purl.org/dc/terms/identifier",
                                                         "http://purl.org/dc/terms/description"}
    with verification_step("a DHF without a workspace has neither graph"):
        bare, bare_results = _record(tmp_path / "bare")
        assert not {"architecture", "code"} & {q.graph_name.value.rsplit("/", 1)[-1]
                                               for q in project(bare, bare_results)}

    with verification_step("RDM's own record: every component projected, the graph's code in the graph component"):
        own = project(ROOT / "dhf", None)
        assert len(_select(own, "SELECT ?c WHERE { ?c a rdm:Component ; rdm:inContext ?x ; rdm:code ?code }")) >= 30
        assert len(_select(own, "SELECT ?s ?t WHERE { ?s rdm:dependsOn ?t }")) >= 20


@allure.story("DI-68")
@allure.label("output", "TODO")
def test_di_68_not_implemented() -> None:
    """DI-68: RDM shall warn, never block, through the graph's gate shapes, when the C4 model
    and the record disagree: a design output in no component's code; a test run that
    exercises a component of a context that neither owns nor realises the design input it
    verifies; a dependency between two components with no relationship declared from the one
    to the other; a component in no container, or in a boundary that is not a container of
    the container diagram; a bounded context of the architecture with no component; a
    component whose code path does not exist; a relationship with no label; and an alias
    declared as non-external in two documents."""
    pytest.fail("DI-68 acceptance test not implemented -- replace this stub with real assertions")


@allure.story("DI-69")
@allure.label("output", "TODO")
def test_di_69_not_implemented() -> None:
    """DI-69: RDM's agent server shall show, in the trace of a design input, the components
    whose code its tests exercise, each with its container and owning bounded context."""
    pytest.fail("DI-69 acceptance test not implemented -- replace this stub with real assertions")
