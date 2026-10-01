"""Acceptance tests for the graph context's design inputs (see dhf/).

DI-35 (the record projected into named RDF graphs) and DI-36 (Oxigraph store,
SPARQL query, SPARQL endpoint), tagged `@allure.story`, over the real
projection, the `rdm graph` commands and a real `oxigraph serve`. Skips cleanly
if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import json
import shutil
import socket
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

from tests.util import git_run

allure = pytest.importorskip("allure")
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


def _record(tmp_path: Path, extra_input: bool = True) -> tuple[Path, Path]:
    """A committed DHF: two needs, two contexts (one realising the other's
    input), a review, tagged tests, and Allure results."""
    repo = tmp_path / "acme"
    docs = repo / "dhf" / "documents"
    (docs / "design").mkdir(parents=True)
    (docs / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\ntitle: V&V plan\nuser_needs:\n"
        "  - {id: UN-001, text: 'a need'}\n  - {id: UN-002, text: 'another need'}\n---\n# Plan\n")
    inputs = "  - id: DI-1\n    text: 'The device shall alarm.'\n    traces_to: [UN-001]\n"
    if extra_input:
        inputs += "  - id: DI-2\n    text: 'The device shall log.'\n    traces_to: [UN-002]\n"
    (docs / "design" / "alarms.md").write_text(
        "---\nid: SDS-ALM-001\ntitle: Alarms design\nrevision: 3\nkind: design\ncontext: alarms\n"
        f"satisfies: [UN-001, UN-002]\ndesign_inputs:\n{inputs}---\n# Alarms\n")
    (docs / "design" / "ui.md").write_text(
        "---\nid: SDS-UI-001\nkind: design\ncontext: ui\nsatisfies: [UN-001]\nrealises: [DI-1]\n"
        "design_inputs: []\n---\n# UI\n")
    (docs / "design_review.md").write_text("---\nid: DR-001\n---\n# Review\nApproved.\n")
    (repo / "tests").mkdir()
    (repo / "tests" / "test_alarms.py").write_text(
        'import allure\n\n@allure.story("DI-1")\ndef test_alarm():\n    assert True\n')
    results = repo / "allure-results"
    results.mkdir()
    (results / "r1-result.json").write_text(json.dumps(
        {"name": "test_alarm", "status": "passed", "labels": [{"name": "story", "value": "DI-1"}]}))
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


@allure.story("DI-35")
@allure.label("output", "rdm/graph/project.py")
def test_record_projects_into_named_graphs(tmp_path: Path) -> None:
    """DI-35: needs, contexts, inputs and documents in the record graph; tags in
    tests; results in executions; latest commits in git; labels on every node;
    the vocabulary in ontology; sorted N-Quads stable across runs."""
    dhf, results = _record(tmp_path)
    quads = project(dhf, results)
    s = _store(quads)
    rec, tst, exe, git, ont = (f"<{G}{n}>" for n in ("record", "tests", "executions", "git", "ontology"))
    di1, ctx = "<urn:dhf:acme:input/DI-1>", "<urn:dhf:acme:context/alarms>"

    # Record graph: needs, contexts, inputs (text, needs, owner, realiser, doc), documents.
    assert _ask(s, f"GRAPH {rec} {{ <urn:dhf:acme:need/UN-001> a rdm:UserNeed ; dcterms:identifier \"UN-001\" ; "
                   f"rdm:text \"a need\" }}")
    assert _ask(s, f"GRAPH {rec} {{ {ctx} a rdm:BoundedContext ; rdm:satisfies <urn:dhf:acme:need/UN-002> }}")
    assert _ask(s, f'GRAPH {rec} {{ {di1} a rdm:DesignInput ; rdm:text "The device shall alarm." ; '
                   f'rdm:tracesTo <urn:dhf:acme:need/UN-001> ; rdm:ownedBy {ctx} ; '
                   f'rdm:declaredIn <urn:dhf:acme:doc/SDS-ALM-001> }}')
    assert not _ask(s, f"GRAPH {rec} {{ {di1} rdm:tracesTo <urn:dhf:acme:need/UN-002> }}")
    assert _ask(s, f"GRAPH {rec} {{ <urn:dhf:acme:context/ui> rdm:realises {di1} }}")
    assert _ask(s, f'GRAPH {rec} {{ <urn:dhf:acme:doc/SDS-ALM-001> a rdm:Document ; '
                   f'dcterms:identifier "SDS-ALM-001" ; dcterms:title "Alarms design" ; rdm:revision "3" }}')

    # Tests graph: the tag, as a tag -- DI-2 has none, and nothing says it is "unverified".
    assert _ask(s, f"GRAPH {tst} {{ ?f a rdm:TestFile ; rdm:verifies {di1} ; rdm:path \"tests/test_alarms.py\" }}")
    assert not _ask(s, "?f rdm:verifies <urn:dhf:acme:input/DI-2>")

    # Executions graph, only when results are given.
    assert _ask(s, f'GRAPH {exe} {{ ?r a rdm:TestRun ; rdm:exercises {di1} ; rdm:status "passed" }}')
    assert not _ask(_store(project(dhf)), "?r a rdm:TestRun")

    # Git graph: the design doc's latest commit, its time and author.
    head = subprocess.run(["git", "-C", str(dhf.parent), "rev-parse", "HEAD"],
                          capture_output=True, text=True, check=True).stdout.strip()
    assert _ask(s, f'GRAPH {git} {{ <urn:dhf:acme:doc/SDS-ALM-001> prov:wasGeneratedBy ?c . '
                   f'?c a prov:Activity ; rdm:sha "{head}" ; prov:endedAtTime ?t ; '
                   f'prov:wasAssociatedWith ?a . ?a a prov:Agent ; rdfs:label ?name }}')

    # Every typed node carries a label.
    assert not _ask(s, "GRAPH ?g { ?n a ?type } FILTER NOT EXISTS { GRAPH ?h { ?n rdfs:label ?l } }")

    # Ontology graph: RDM's vocabulary, reusing OSLC RM.
    assert _ask(s, f"GRAPH {ont} {{ rdm:DesignInput rdfs:subClassOf oslc_rm:Requirement }}")

    # Sorted N-Quads, byte-identical for an unchanged record.
    text = nquads(quads)
    assert text.splitlines() == sorted(text.splitlines())
    assert nquads(list(reversed(quads))) == text  # sorted regardless of input order
    assert nquads(project(dhf, results)) == text


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@allure.story("DI-36")
@allure.label("output", "rdm/graph/cli.py")
def test_store_query_and_serve(tmp_path: Path, capsys) -> None:
    """DI-36: the store is replaced (never merged) on each build; queries run
    over the store or an in-memory projection; `serve` exposes the store as a
    SPARQL endpoint whose default graph is the union of the named graphs."""
    dhf, results = _record(tmp_path)
    location = tmp_path / "store"
    assert graph_cli.graph_build_command(dhf_dir=dhf, allure_results_dir=results, store=location) == 0

    # Query over the store (standard prefixes need no declaration; no GRAPH clause).
    capsys.readouterr()
    sparql = "SELECT ?id WHERE { ?i a rdm:DesignInput ; dcterms:identifier ?id } ORDER BY ?id"
    assert graph_cli.graph_query_command(sparql, store=location) == 0
    assert capsys.readouterr().out.splitlines() == ["?id", '"DI-1"', '"DI-2"']

    # Rebuild after DI-2 is removed: replaced, not merged.
    dhf2, _ = _record(tmp_path / "v2", extra_input=False)
    assert build_store(location, project(dhf2, project_name="acme")) > 0
    assert graph_cli.graph_query_command(sparql, store=location) == 0
    assert capsys.readouterr().out.splitlines() == ["?id", '"DI-1"']

    # In-memory projection when no store is given; ASK and CONSTRUCT too.
    assert graph_cli.graph_query_command(sparql, dhf_dir=dhf) == 0
    assert capsys.readouterr().out.splitlines() == ["?id", '"DI-1"', '"DI-2"']
    assert graph_cli.graph_query_command("ASK { ?r rdm:exercises ?i }", store=location) == 0
    assert capsys.readouterr().out.strip() == "false"  # v2 was built without results
    assert graph_cli.graph_query_command(
        "CONSTRUCT { ?i rdm:tracesTo ?n } WHERE { ?i rdm:tracesTo ?n }", dhf_dir=dhf) == 0
    assert "<urn:dhf:acme:input/DI-1> <https://github.com/scope-impact/rdm/ns#tracesTo>" in capsys.readouterr().out

    # Serve: union default graph + CORS, over a real `oxigraph serve`.
    if shutil.which("oxigraph") is None:
        pytest.skip("oxigraph CLI not installed")
    port = _free_port()
    args = graph_cli.serve_args(location, f"127.0.0.1:{port}")
    assert {"--union-default-graph", "--cors"} <= set(args) and str(location) in args
    server = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        url = f"http://127.0.0.1:{port}/sparql?" + urllib.parse.urlencode(
            {"query": PREFIXES + "SELECT ?l WHERE { ?n a rdm:UserNeed ; rdfs:label ?l } ORDER BY ?l"})
        for _ in range(60):
            try:
                with urllib.request.urlopen(urllib.request.Request(
                        url, headers={"Accept": "text/csv", "Origin": "http://localhost"}), timeout=2) as response:
                    body = response.read().decode()
                    cors = response.headers.get("Access-Control-Allow-Origin")
                break
            except OSError:
                time.sleep(0.25)
        else:
            pytest.fail("oxigraph serve did not come up")
    finally:
        server.terminate()
        server.wait(timeout=10)
    # No GRAPH clause, yet the named-graph facts are visible: the union default graph.
    assert body.split() == ["l", "UN-001", "UN-002"]
    assert cors == "*"

    # A missing store is refused, not created.
    assert graph_cli.graph_serve_command(store=tmp_path / "nope") == 2
