"""Acceptance tests for the graph context's requirements (see .pi/spec/graph).

item-06-001 (the record projected into named RDF graphs) and item-06-002
(Oxigraph store, SPARQL query, SPARQL endpoint), tagged `@allure.tag`, over
the real projection, the `rdm graph` commands and the served endpoint. Skips cleanly
if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import json
import socket
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

from tests.util import git_run, write_spec

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402
ox = pytest.importorskip("pyoxigraph")

from rdm.graph import cli as graph_cli  # noqa: E402
from rdm.graph.project import build_store, nquads, project  # noqa: E402

PREFIXES = """PREFIX rdm: <https://github.com/scope-impact/rdm/ns#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX dcterms: <http://purl.org/dc/terms/>
PREFIX prov: <http://www.w3.org/ns/prov#>
PREFIX oslc_rm: <http://open-services.net/ns/rm#>
"""
G = "urn:dhf:acme:graph/"


ALARM_ALLIUM = """-- a need
surface Alarm {
    facing nurse: Nurse

    provides:
        SoundAlarm(device)

    @guarantee Alarm
}

-- another need
surface Logging {
    facing nurse: Nurse

    @guarantee Logging
}

rule SoundAlarm {
    when: SoundAlarm(device)
}
"""


def _record(tmp_path: Path, extra_input: bool = True, layout: str = "gherkin") -> tuple[Path, Path]:
    """A committed repository: a spec of the context alarms (two needs and
    their requirements), controlled documents, and Allure results.
    item-01-001 addresses the need Alarm; item-01-002, when ``extra_input``,
    Logging. In plain Gherkin (the default) each need is a Feature; with
    ``layout="loyly"`` each is an Allium surface, item-01-001 also covers the
    rule SoundAlarm, and a second context, ui, holds no requirement."""
    repo = tmp_path / "acme"
    docs = repo / "dhf" / "documents"
    (docs / "design").mkdir(parents=True)
    items = [("item-01-001", "The device shall alarm.", ["Alarm"])]
    if extra_input:
        items.append(("item-01-002", "The device shall log.", ["Logging"]))
    write_spec(repo, tuple(items), {"Alarm": ("alarms", "Nurse", "a need"),
                                    "Logging": ("alarms", "Nurse", "another need")},
               {"01": "alarms", "02": "ui"} if layout == "loyly" else {"01": "alarms"}, layout=layout)
    if layout == "loyly":
        feature = repo / ".pi" / "spec" / "alarms" / "spec.feature"
        feature.write_text(feature.read_text().replace(
            "@allium-guarantee.Alarm", "@allium-guarantee.Alarm @allium-rule-success.SoundAlarm"))
        (repo / ".pi" / "spec" / "alarms" / "allium" / "needs.allium").write_text(ALARM_ALLIUM)
    (docs / "verification_and_validation_plan.md").write_text("---\nid: VVP-001\ntitle: V&V plan\n---\n# Plan\n")
    (docs / "design" / "alarms.md").write_text(
        "---\nid: SDS-ALM-001\ntitle: Alarms design\nrevision: 3\n---\n# Alarms\n")
    (docs / "design_review.md").write_text("---\nid: DR-001\n---\n# Review\nApproved.\n")
    results = repo / "allure-results"
    results.mkdir()
    (results / "r1-result.json").write_text(json.dumps(
        {"name": "test_alarm", "status": "passed", "labels": [{"name": "tag", "value": "item-01-001"}]}))
    git_run(repo, "init")
    git_run(repo, "add", "-A")
    git_run(repo, "commit", "-m", "approve design")
    return repo / "dhf", results


def _store(quads) -> "ox.Store":
    store = ox.Store()
    store.extend(quads)
    return store


def _ask(store, body: str) -> bool:
    # Patterns without a GRAPH clause match across all named graphs, as
    # `rdm graph query` and the served endpoint do.
    return bool(store.query(PREFIXES + "ASK { " + body + " }", use_default_graph_as_union=True))


@allure.tag("item-06-001")
@allure.label("output", "rdm/graph/project.py")
def test_record_projects_into_named_graphs(tmp_path: Path) -> None:
    """item-06-001: the spec's user needs, requirements, Allium rules and
    contexts, and the controlled documents, in the record graph; results in
    executions; latest commits in git; labels on every node; the vocabulary in
    ontology; sorted N-Quads stable across runs."""
    dhf, results = _record(tmp_path)
    quads = project(dhf, results)
    s = _store(quads)
    rec, exe, git, ont = (f"<{G}{n}>" for n in ("record", "executions", "git", "ontology"))
    req1, ctx = "<urn:dhf:acme:requirement/item-01-001>", "<urn:dhf:acme:context/alarms>"
    alarm = "<urn:dhf:acme:need/Alarm>"

    with verification_step("Record graph: user needs (each Feature: its title the id, its description the text)"):
        assert _ask(s, f'GRAPH {rec} {{ {alarm} a rdm:UserNeed ; dcterms:identifier "Alarm" ; rdm:text "a need" ; '
                       f'rdm:ownedBy {ctx} ; rdm:source "spec/alarms/Alarm.feature:1" }}')
    with verification_step("Record graph: requirements (text, needs, context, source line) and contexts"):
        assert _ask(s, f'GRAPH {rec} {{ {req1} a rdm:Requirement ; dcterms:identifier "item-01-001" ; '
                       f'rdm:text "The device shall alarm." ; rdm:tracesTo {alarm} ; rdm:addresses {alarm} ; '
                       f'rdm:ownedBy {ctx} ; rdm:source "spec/alarms/Alarm.feature:5" . {ctx} a rdm:BoundedContext }}')
        assert not _ask(s, f"GRAPH {rec} {{ {req1} rdm:tracesTo <urn:dhf:acme:need/Logging> }}")
        assert _ask(s, "<urn:dhf:acme:requirement/item-01-002> rdm:tracesTo <urn:dhf:acme:need/Logging>")
    with verification_step("With an adapter's needs (Löyly's layout, Allium): what a need faces, provides and "
                           "guarantees, the rules a requirement covers, Allium rules, a context with no requirement"):
        dhf_l, _ = _record(tmp_path / "loyly", layout="loyly")
        sl = _store(project(dhf_l))
        assert _ask(sl, f'GRAPH {rec} {{ {alarm} a rdm:UserNeed ; rdm:facing "Nurse" ; rdm:surface "Alarm" ; '
                        f'rdm:guarantee "Alarm" ; rdm:providesRule <urn:dhf:acme:rule/SoundAlarm> ; rdm:ownedBy {ctx} }}')
        assert _ask(sl, f'GRAPH {rec} {{ {req1} rdm:covers "guarantee.Alarm" ; '
                        f'rdm:coversRule <urn:dhf:acme:rule/SoundAlarm> ; rdm:addresses {alarm} ; '
                        f'rdm:source ".pi/plan/01-alarms/spec.feature:5" }}')
        assert _ask(sl, f'GRAPH {rec} {{ <urn:dhf:acme:rule/SoundAlarm> a rdm:AlliumRule ; '
                        f'rdm:trigger "SoundAlarm" ; rdm:ownedBy {ctx} . <urn:dhf:acme:context/ui> a rdm:BoundedContext }}')
    with verification_step("Record graph: controlled documents"):
        assert _ask(s, f'GRAPH {rec} {{ <urn:dhf:acme:doc/SDS-ALM-001> a rdm:Document ; '
                       f'dcterms:identifier "SDS-ALM-001" ; dcterms:title "Alarms design" ; rdm:revision "3" }}')

    with verification_step("Executions graph, only when results are given"):
        assert _ask(s, f'GRAPH {exe} {{ ?r a rdm:TestRun ; rdm:exercises {req1} ; rdm:status "passed" }}')
        assert not _ask(_store(project(dhf)), "?r a rdm:TestRun")

    with verification_step("Git graph: the design doc's latest commit, its time and author"):
        head = subprocess.run(["git", "-C", str(dhf.parent), "rev-parse", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
        assert _ask(s, f'GRAPH {git} {{ <urn:dhf:acme:doc/SDS-ALM-001> prov:wasGeneratedBy ?c . '
                       f'?c a prov:Activity ; rdm:sha "{head}" ; prov:endedAtTime ?t ; '
                       f'prov:wasAssociatedWith ?a . ?a a prov:Agent ; rdfs:label ?name }}')

    with verification_step("Every typed node carries a label"):
        assert not _ask(s, "GRAPH ?g { ?n a ?type } FILTER NOT EXISTS { GRAPH ?h { ?n rdfs:label ?l } }")

    with verification_step("Ontology graph: RDM's vocabulary, reusing OSLC RM"):
        assert _ask(s, f"GRAPH {ont} {{ rdm:Requirement rdfs:subClassOf oslc_rm:Requirement }}")

    with verification_step("Sorted N-Quads, byte-identical for an unchanged record"):
        text = nquads(quads)
        assert text.splitlines() == sorted(text.splitlines())
        assert nquads(list(reversed(quads))) == text  # sorted regardless of input order
        assert nquads(project(dhf, results)) == text
    with verification_step("A literal holding a Unicode line separator stays one statement"):
        odd = [ox.Quad(ox.NamedNode("urn:a"), ox.NamedNode("urn:b"), ox.Literal("one\u2028two\x85three"))]
        assert list(ox.parse(nquads(odd).encode(), format=ox.RdfFormat.N_QUADS)) == odd


@allure.tag("item-06-002")
@allure.label("output", "rdm/graph/cli.py")
def test_store_query_and_serve(tmp_path: Path, capsys) -> None:
    """item-06-002: the store is replaced (never merged) on each build; queries run
    over the store or an in-memory projection; `serve` exposes the store as a
    SPARQL endpoint whose default graph is the union of the named graphs."""
    dhf, results = _record(tmp_path)
    location = tmp_path / "store"
    assert graph_cli.graph_build_command(dhf_dir=dhf, allure_results_dir=results, store=location) == 0

    with verification_step("Query over the store (standard prefixes need no declaration; no GRAPH clause)"):
        capsys.readouterr()
        sparql = "SELECT ?id WHERE { ?i a rdm:Requirement ; dcterms:identifier ?id } ORDER BY ?id"
        assert graph_cli.graph_query_command(sparql, store=location) == 0
        assert capsys.readouterr().out.splitlines() == ["?id", '"item-01-001"', '"item-01-002"']

    with verification_step("Rebuild after item-01-002 is removed: replaced, not merged"):
        dhf2, _ = _record(tmp_path / "v2", extra_input=False)
        assert build_store(location, project(dhf2, project_name="acme")) > 0
        assert graph_cli.graph_query_command(sparql, store=location) == 0
        assert capsys.readouterr().out.splitlines() == ["?id", '"item-01-001"']

    with verification_step("In-memory projection when no store is given; ASK and CONSTRUCT too"):
        assert graph_cli.graph_query_command(sparql, dhf_dir=dhf) == 0
        assert capsys.readouterr().out.splitlines() == ["?id", '"item-01-001"', '"item-01-002"']
        assert graph_cli.graph_query_command("ASK { ?r rdm:exercises ?i }", store=location) == 0
        assert capsys.readouterr().out.strip() == "false"  # v2 was built without results
        assert graph_cli.graph_query_command(
            "CONSTRUCT { ?i rdm:tracesTo ?n } WHERE { ?i rdm:tracesTo ?n }", dhf_dir=dhf) == 0
        traced = "<urn:dhf:acme:requirement/item-01-001> <https://github.com/scope-impact/rdm/ns#tracesTo>"
        assert traced in capsys.readouterr().out

    with verification_step("Query refuses SPARQL Update and SERVICE: nothing changes, nothing reaches the network"):
        for refused in ("CLEAR ALL", "SELECT * WHERE { SERVICE <http://127.0.0.1:9/x> { ?s ?p ?o } }"):
            assert graph_cli.graph_query_command(refused, store=location) == 2
        capsys.readouterr()

    with verification_step("FROM, which the union default graph would ignore, is refused; SERVICE as a variable or a "
                           "prefix name is no call"):
        for refused in ("SELECT * FROM <urn:nothing> WHERE { ?s ?p ?o }",
                        "select * from named <urn:x> where { graph ?g { ?s ?p ?o } }"):
            assert graph_cli.graph_query_command(refused, store=location) == 2
            assert "GRAPH" in capsys.readouterr().out
        for accepted in ("SELECT ?SERVICE WHERE { ?SERVICE ?p ?o } LIMIT 1",
                         "PREFIX SERVICE: <urn:x#> SELECT * WHERE { ?s SERVICE:p ?o }",
                         'SELECT * WHERE { ?s rdfs:label "FROM <urn:x>" }'):
            assert graph_cli.graph_query_command(accepted, store=location) == 0, accepted

    from rdm.graph.endpoint import endpoint

    server = endpoint(location, "127.0.0.1", 0)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"

    def get(sparql: str, accept: str = "text/csv"):
        url = f"{base}/sparql?" + urllib.parse.urlencode({"query": sparql})
        return urllib.request.urlopen(urllib.request.Request(
            url, headers={"Accept": accept, "Origin": "http://localhost"}), timeout=5)

    def refused(request) -> int:
        with pytest.raises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(request, timeout=5)
        return error.value.code

    labels = "SELECT ?l WHERE { ?n a rdm:Requirement ; rdfs:label ?l } ORDER BY ?l"
    try:
        with verification_step("Serve: the union default graph, standard prefixes, CORS open"):
            with get(labels) as response:
                body = response.read().decode()
                cors = response.headers.get("Access-Control-Allow-Origin")
            assert body.split() == ["l", "item-01-001"] and cors == "*"  # the v2 build: item-01-002 removed
            with get("ASK { ?s ?p ?o }", "application/sparql-results+json") as response:
                assert json.loads(response.read())["boolean"] is True
        with verification_step("Serve refuses an update from another origin, and the data stays"):
            update = urllib.request.Request(
                f"{base}/update", method="POST", data=urllib.parse.urlencode({"update": "CLEAR ALL"}).encode(),
                headers={"Origin": "https://elsewhere.example", "Content-Type": "application/x-www-form-urlencoded"})
            assert refused(update) == 403
            with get(labels) as response:
                assert response.read().decode().split() == ["l", "item-01-001"]
        with verification_step("Serve refuses SERVICE, a comment ended by a carriage return included"):
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            listener.settimeout(0.5)
            target = f"http://127.0.0.1:{listener.getsockname()[1]}/x"
            for sparql in (f"SELECT * WHERE {{ SERVICE <{target}> {{ ?s ?p ?o }} }}",
                           f"SELECT * WHERE {{ # c\rSERVICE <{target}> {{ ?s ?p ?o }}\n}}"):
                assert refused(urllib.request.Request(
                    f"{base}/sparql?" + urllib.parse.urlencode({"query": sparql}))) == 400
            with pytest.raises(OSError):  # nothing reached the listener
                listener.accept()
            listener.close()
        with verification_step("Serve answers from the last build"):
            assert build_store(location, project(dhf, results, project_name="acme")) > 0
            with get(labels) as response:
                assert response.read().decode().split() == ["l", "item-01-001", "item-01-002"]
    finally:
        server.shutdown()
        server.server_close()

    with verification_step("A missing store is refused, not created"):
        assert graph_cli.graph_serve_command(store=tmp_path / "nope") == 2
