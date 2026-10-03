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

from rdm.architecture.model import read_model  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from rdm.kernel.frontmatter import parse_frontmatter  # noqa: E402
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


def _named_run(results: Path, stem: str, story: str, *components: str) -> None:
    """A passed run of ``story`` naming each of ``components`` by a component label."""
    labels = [{"name": "story", "value": story}] + [{"name": "component", "value": c} for c in components]
    (results / f"{stem}-result.json").write_text(json.dumps({
        "fullName": "tests.test_alarms#test_alarm", "name": stem, "status": "passed", "labels": labels}))


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
    matching code path); and, for a component whose code is Python, an import from its code
    into another component's as a dependency between the two, marking a component whose code
    holds no Python as one whose dependencies were not read."""
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
    with verification_step("a component whose code holds no Python is marked as one whose dependencies were not read"):
        workspace = dhf / "c4" / "workspace.json"
        model = json.loads(workspace.read_text())
        model["model"]["softwareSystems"][0]["containers"][0]["components"].append(
            {"id": "7", "name": "Sounds", "group": "alarms",
             "properties": {"structurizr.dsl.identifier": "sounds", "code": "web/"}})
        workspace.write_text(json.dumps(model))
        (dhf.parent / "web").mkdir()
        (dhf.parent / "web" / "sounds.ts").write_text("import { beep } from '../src/speaker';\n")
        quads = project(dhf, results)
        assert _select(quads, "SELECT ?c WHERE { ?c rdm:dependenciesNotRead true }") == {("element/sounds",)}
        assert not _select(quads, "SELECT ?t WHERE { <urn:dhf:acme:element/sounds> rdm:dependsOn ?t }")
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


# The warnings DI-68's shapes give, by the start of their message.
C4_WARNINGS = ("design output is in no component's code", "test run names a component of",
               "code dependency with no relationship", "component's group", "bounded context has no component",
               "bounded context's design document does not show", "component's code path", "relationship has no")


def _c4_warnings(quads) -> set[tuple[str, str, str]]:
    from rdm.graph.validate import validate

    return {(r.severity, r.label, r.message) for r in validate(quads) if r.message.startswith(C4_WARNINGS)}


def _agreeing(repo: Path) -> None:
    """The acme record made to agree with its workspace: each design document
    shows its component view, and an architecture document declares the two
    contexts the components are grouped in."""
    docs = repo / "dhf" / "documents"
    for ctx in ("alarms", "ui"):
        doc = docs / "design" / f"{ctx}.md"
        doc.write_text(doc.read_text() + f"\n![Components: {ctx}](../../c4/views/C3_{ctx}.svg)\n")
    (docs / "architecture.md").write_text("---\nid: SDS-SYS-001\ncontexts: [alarms, ui]\n---\n# Architecture\n")


@allure.story("DI-68")
@allure.label("output", "rdm/graph/shapes.ttl")
@allure.label("output", "rdm/graph/c4.py")
def test_the_c4_model_and_the_record_are_checked_against_each_other(tmp_path: Path) -> None:
    """DI-68: RDM shall warn, never block, through the graph's gate shapes, when the C4 model
    and the record disagree: a design output in no component's code; a test run that names a
    component of a context that neither owns nor realises the design input it verifies (a
    component it only reaches is never one); a dependency read from the code between two
    components with no relationship declared from the one to the other; a group of components
    that is not a bounded context of the architecture, or a bounded context with no component;
    a bounded context whose design document does not show its component view; a component
    whose code path does not exist; and a relationship with no description."""
    dhf, results = _record(tmp_path / "agree")
    _results(results)
    _workspace(dhf.parent)
    _agreeing(dhf.parent)
    with verification_step("a record that agrees with its C4 model gets no warning"):
        assert _c4_warnings(project(dhf, results)) == set()  # run b exercises ui's code for DI-1, which ui realises

    with verification_step("a component of another context a run only reaches raises no warning; one it names does"):
        _named_run(results, "reaching", "DI-2", "alarms")  # alarms owns DI-2; alarms -> app, of ui
        quads = project(dhf, results, infer=True)
        assert ("run/reaching-result", "element/app") in _select(quads, "SELECT ?r ?c WHERE { ?r rdm:reaches ?c }")
        assert _c4_warnings(quads) == set()
        _named_run(results, "naming", "DI-2", "app")
        assert {(label, message) for _, label, message in _c4_warnings(project(dhf, results, infer=True))} == {
            ("naming", "test run names a component of ui, which neither owns nor realises DI-2")}

    dhf, results = _record(tmp_path / "disagree")
    _results(results)
    _workspace(dhf.parent)
    _agreeing(dhf.parent)
    repo = dhf.parent
    docs = dhf / "documents"
    (docs / "design" / "ui.md").write_text((docs / "design" / "ui.md").read_text().split("![Components")[0])
    (docs / "architecture.md").write_text("---\nid: SDS-SYS-001\ncontexts: [alarms, ui, billing]\n---\n")
    (repo / "src" / "speaker.py").write_text("from src.alarms import beep\n")  # app now imports alarms
    (repo / "src" / "log.py").write_text("")
    workspace = repo / "dhf" / "c4" / "workspace.json"
    model = json.loads(workspace.read_text())
    firmware = model["model"]["softwareSystems"][0]["containers"][0]
    firmware["components"] += [
        {"id": "7", "name": "Logger", "group": "logging", "properties": {
            "structurizr.dsl.identifier": "logger", "code": "src/log.py"}},
        {"id": "8", "name": "Ghost", "group": "alarms", "properties": {
            "structurizr.dsl.identifier": "ghost", "code": "src/ghost.py"}}]
    model["model"]["people"][0]["relationships"].append({"id": "11", "sourceId": "1", "destinationId": "5"})
    workspace.write_text(json.dumps(model))
    common = {"fullName": "tests.test_alarms#test_alarm", "name": "test_alarm", "status": "passed"}
    (results / "x-result.json").write_text(json.dumps({**common, "uuid": "u-x", "labels": [
        {"name": "story", "value": "DI-2"}, {"name": "output", "value": "src/speaker.py"},
        {"name": "output", "value": "lib/stray.py"}]}))
    found = _c4_warnings(project(dhf, results))
    attach("warnings", sorted(found))
    with verification_step("each disagreement gets its warning, on what disagrees"):
        assert {(label, message) for _, label, message in found} == {
            ("lib/stray.py", "design output is in no component's code"),
            ("test_alarm", "test run names a component of ui, which neither owns nor realises DI-2"),
            ("app imports alarms", "code dependency with no relationship declared from the one component to the other"),
            ("Logger", "component's group logging is not a bounded context the architecture declares"),
            ("billing", "bounded context has no component in the architecture"),
            ("ui", "bounded context's design document does not show its component view C3_ui"),
            ("Ghost", "component's code path src/ghost.py does not exist"),
            ("nurse -> app", "relationship has no description"),
        }
    with verification_step("every one is a warning, never a violation"):
        assert {severity for severity, _, _ in found} == {"Warning"}

    with verification_step("RDM's own C4 model and record agree, its views named by convention: a component view "
                           "for each context, dynamic views named for one"):
        own = _c4_warnings(project(ROOT / "dhf", None))
        attach("RDM's own", sorted(own))
        assert own == set()
        views = set(read_model(ROOT / "dhf").views)
        architecture = parse_frontmatter((ROOT / "dhf" / "documents" / "architecture.md").read_text())
        declared = {c["id"] for c in architecture["contexts"]}
        static = {"C1", "C2"} | {f"C3_{context}" for context in declared}
        assert static <= views
        dynamic = views - static
        assert all(any(key.startswith(f"D_{context}_") for context in declared) for key in dynamic), dynamic


@allure.story("DI-69")
@allure.label("output", "rdm/graph/agent.py")
def test_the_trace_names_the_components_a_design_input_exercises(tmp_path: Path) -> None:
    """DI-69: RDM's agent server shall show, in the trace of a design input, the components
    its runs name and, apart, the components they reach and the components their coverage
    shows they ran, each with its container and owning bounded context."""
    from rdm.graph.agent import Record, trace

    dhf, results = _record(tmp_path)
    _results(results)
    _workspace(dhf.parent)
    with verification_step("each component its runs name, with its container and context"):
        components = trace(Record(dhf, results), "DI-1")["design_input"]["components"]
        attach("components of DI-1", components)
        assert components == [
            {"component": "alarms", "name": "Alarm logic", "container": "Firmware", "context": "alarms"},
            {"component": "app", "name": "Device app", "container": "Firmware", "context": "ui"}]
    with verification_step("an input no run exercises names none"):
        assert trace(Record(dhf, results), "DI-2")["design_input"]["components"] == []
    with verification_step("the components their coverage shows they ran are listed apart from named and reached"):
        from tests.acceptance.test_coverage import _covered_run

        _covered_run(results, "ran", "DI-2", "coverage", "SF:src/speaker.py\nDA:1,1\nend_of_record\n")
        traced = trace(Record(dhf, results), "DI-2")["design_input"]
        attach("trace DI-2, coverage only", traced)
        assert traced["covered_components"] == [
            {"component": "app", "name": "Device app", "container": "Firmware", "context": "ui"}]
        assert traced["components"] == [] and traced["reached_components"] == []
    with verification_step("the components its runs reach are listed apart from those they name"):
        _named_run(results, "reaching", "DI-2", "alarms")  # alarms -> app
        traced = trace(Record(dhf, results), "DI-2")["design_input"]
        attach("trace DI-2", traced)
        assert traced["components"] == [
            {"component": "alarms", "name": "Alarm logic", "container": "Firmware", "context": "alarms"}]
        assert traced["reached_components"] == [
            {"component": "app", "name": "Device app", "container": "Firmware", "context": "ui"}]


@allure.story("DI-73")
@allure.label("output", "rdm/graph/ontology.ttl")
@allure.label("output", "rdm/graph/c4.py")
@allure.label("output", "rdm/graph/agent.py")
def test_a_run_reaches_what_the_components_it_names_lead_to(tmp_path: Path) -> None:
    """DI-73: RDM shall derive, by a rule its vocabulary declares, that a test run reaches each
    component the C4 model's declared relationships lead to, at any depth, from a component the
    run names, and that is not itself named; the record never states it, and a reached
    component is never taken for a named one."""
    from rdm.graph.agent import schema

    dhf, results = _record(tmp_path)
    _results(results)
    _workspace(dhf.parent)  # nurse -> alarms -> app
    workspace = dhf / "c4" / "workspace.json"
    model = json.loads(workspace.read_text())
    components = model["model"]["softwareSystems"][0]["containers"][0]["components"]
    components[1]["relationships"] = [{"id": "11", "sourceId": "5", "destinationId": "7", "description": "logs"},
                                      {"id": "12", "sourceId": "5", "destinationId": "6", "description": "records"}]
    components.append({"id": "7", "name": "Logger", "group": "ui",
                       "properties": {"structurizr.dsl.identifier": "logger", "code": "src/log.py"}})
    workspace.write_text(json.dumps(model))  # alarms -> app -> logger; app -> the EHR, a software system
    _named_run(results, "chain", "DI-2", "alarms")
    _named_run(results, "both", "DI-2", "alarms", "app")
    (dhf.parent / "src" / "log.py").write_text("from src.alarms import beep\n")  # a code dependency, not declared
    _named_run(results, "leaf", "DI-2", "logger")
    quads = project(dhf, results, infer=True)
    reached = _select(quads, "SELECT ?r ?c WHERE { ?r rdm:reaches ?c }")
    attach("reached", sorted(reached))

    with verification_step("a run reaches each component the declared relationships lead to, at any depth"):
        assert {c for r, c in reached if r == "run/chain-result"} == {"element/app", "element/logger"}
    with verification_step("a component the run names is never reached, nor a person, system or container"):
        assert {c for r, c in reached if r == "run/both-result"} == {"element/logger"}
        assert not {c for r, c in reached if r == "run/leaf-result"}  # logger imports alarms: no relationship
        assert not any(c in ("element/alarms", "element/nurse", "element/ehr", "element/firmware")
                       for _, c in reached)
    with verification_step("reached facts are derived into the inferred graph only, never stated by the record"):
        assert {q.graph_name.value for q in quads if q.predicate.value == RDM + "reaches"} == {
            "urn:dhf:acme:graph/inferred"}
        assert not any(q.predicate.value == RDM + "reaches" for q in project(dhf, results))
    with verification_step("the vocabulary declares the rule, and the agent server's schema lists it"):
        rule = {r["rule"]: r for r in schema()["rules"]}[RDM + "ReachesRule"]
        attach("rdm:ReachesRule", rule)
        assert rule["derives"] == RDM + "reaches" and rule["construct"].lstrip().startswith("CONSTRUCT")
