"""Acceptance tests for the graph context's design inputs (see dhf/).

DI-35 (the record projected into named RDF graphs) and DI-36 (the graph store,
SPARQL query, SPARQL endpoint), tagged `@allure.story`, over the real
projection, the `rdm graph` commands and the served endpoint. Skips cleanly
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

from tests.util import git_run

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402
pytest.importorskip("rdflib")
from rdm.graph import rdf as ox  # noqa: E402

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
        f"design_inputs:\n{inputs}---\n# Alarms\n")
    (docs / "design" / "ui.md").write_text(
        "---\nid: SDS-UI-001\nkind: design\ncontext: ui\nrealises: [DI-1]\n"
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

    with verification_step("Record graph: needs, contexts, inputs (text, needs, owner, realiser, doc), documents"):
        assert _ask(s, f"GRAPH {rec} {{ <urn:dhf:acme:need/UN-001> a rdm:UserNeed ; dcterms:identifier \"UN-001\" ; "
                       f"rdm:text \"a need\" }}")
        assert _ask(s, f"GRAPH {rec} {{ {ctx} a rdm:BoundedContext }}")
        # a context's needs are not declared: they follow from its inputs (DI-1)
        assert not _ask(s, "?c rdm:satisfies ?n")
        assert _ask(s, f"?i rdm:ownedBy {ctx} ; rdm:tracesTo <urn:dhf:acme:need/UN-002>")
        assert _ask(s, f'GRAPH {rec} {{ {di1} a rdm:DesignInput ; rdm:text "The device shall alarm." ; '
                       f'rdm:tracesTo <urn:dhf:acme:need/UN-001> ; rdm:ownedBy {ctx} ; '
                       f'rdm:declaredIn <urn:dhf:acme:doc/SDS-ALM-001> }}')
        assert not _ask(s, f"GRAPH {rec} {{ {di1} rdm:tracesTo <urn:dhf:acme:need/UN-002> }}")
        assert _ask(s, f"GRAPH {rec} {{ <urn:dhf:acme:context/ui> rdm:realises {di1} }}")
        assert _ask(s, f'GRAPH {rec} {{ <urn:dhf:acme:doc/SDS-ALM-001> a rdm:Document ; '
                       f'dcterms:identifier "SDS-ALM-001" ; dcterms:title "Alarms design" ; rdm:revision "3" }}')

    with verification_step("Tests graph: the tag, as a tag -- DI-2 has none, and nothing says it is \"unverified\""):
        assert _ask(s, f"GRAPH {tst} {{ ?t a rdm:Test ; rdm:verifies {di1} ; rdm:definedIn ?f . "
                       f"?f a rdm:TestFile ; rdm:path \"tests/test_alarms.py\" }}")
        assert not _ask(s, "?f rdm:verifies <urn:dhf:acme:input/DI-2>")

    with verification_step("Executions graph, only when results are given"):
        assert _ask(s, f'GRAPH {exe} {{ ?r a rdm:TestRun ; rdm:exercises {di1} ; rdm:status "passed" }}')
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
        assert _ask(s, f"GRAPH {ont} {{ rdm:DesignInput rdfs:subClassOf oslc_rm:Requirement }}")

    with verification_step("Sorted N-Quads, byte-identical for an unchanged record"):
        text = nquads(quads)
        assert text.splitlines() == sorted(text.splitlines())
        assert nquads(list(reversed(quads))) == text  # sorted regardless of input order
        assert nquads(project(dhf, results)) == text
    with verification_step("A literal holding a Unicode line separator stays one statement"):
        odd = [ox.Quad(ox.NamedNode("urn:a"), ox.NamedNode("urn:b"), ox.Literal("one\u2028two\x85three"))]
        assert list(ox.parse(nquads(odd).encode(), format=ox.RdfFormat.N_QUADS)) == odd


@allure.story("DI-36")
@allure.label("output", "rdm/graph/cli.py")
def test_store_query_and_serve(tmp_path: Path, capsys) -> None:
    """DI-36: the store is replaced (never merged) on each build; queries run
    over the store or an in-memory projection; `serve` exposes the store as a
    SPARQL endpoint whose default graph is the union of the named graphs."""
    dhf, results = _record(tmp_path)
    location = tmp_path / "store"
    assert graph_cli.graph_build_command(dhf_dir=dhf, allure_results_dir=results, store=location) == 0

    with verification_step("Query over the store (standard prefixes need no declaration; no GRAPH clause)"):
        capsys.readouterr()
        sparql = "SELECT ?id WHERE { ?i a rdm:DesignInput ; dcterms:identifier ?id } ORDER BY ?id"
        assert graph_cli.graph_query_command(sparql, store=location) == 0
        assert capsys.readouterr().out.splitlines() == ["?id", '"DI-1"', '"DI-2"']

    with verification_step("Rebuild after DI-2 is removed: replaced, not merged"):
        dhf2, _ = _record(tmp_path / "v2", extra_input=False)
        assert build_store(location, project(dhf2, project_name="acme")) > 0
        assert graph_cli.graph_query_command(sparql, store=location) == 0
        assert capsys.readouterr().out.splitlines() == ["?id", '"DI-1"']

    with verification_step("In-memory projection when no store is given; ASK and CONSTRUCT too"):
        assert graph_cli.graph_query_command(sparql, dhf_dir=dhf) == 0
        assert capsys.readouterr().out.splitlines() == ["?id", '"DI-1"', '"DI-2"']
        assert graph_cli.graph_query_command("ASK { ?r rdm:exercises ?i }", store=location) == 0
        assert capsys.readouterr().out.strip() == "false"  # v2 was built without results
        assert graph_cli.graph_query_command(
            "CONSTRUCT { ?i rdm:tracesTo ?n } WHERE { ?i rdm:tracesTo ?n }", dhf_dir=dhf) == 0
        assert "<urn:dhf:acme:input/DI-1> <https://github.com/scope-impact/rdm/ns#tracesTo>" in capsys.readouterr().out

    with verification_step("Query refuses SPARQL Update and SERVICE: nothing changes, nothing reaches the network"):
        for refused in ("CLEAR ALL", "SELECT * WHERE { SERVICE <http://127.0.0.1:9/x> { ?s ?p ?o } }"):
            assert graph_cli.graph_query_command(refused, store=location) == 2
        capsys.readouterr()

    with verification_step("FROM, which the union default graph would ignore, is refused; SERVICE as a variable or a "
                           "prefix name is no call; --infer over a store is refused"):
        for refused in ("SELECT * FROM <urn:nothing> WHERE { ?s ?p ?o }",
                        "select * from named <urn:x> where { graph ?g { ?s ?p ?o } }"):
            assert graph_cli.graph_query_command(refused, store=location) == 2
            assert "GRAPH" in capsys.readouterr().out
        for accepted in ("SELECT ?SERVICE WHERE { ?SERVICE ?p ?o } LIMIT 1",
                         "PREFIX SERVICE: <urn:x#> SELECT * WHERE { ?s SERVICE:p ?o }",
                         'SELECT * WHERE { ?s rdfs:label "FROM <urn:x>" }'):
            assert graph_cli.graph_query_command(accepted, store=location) == 0, accepted
        capsys.readouterr()
        assert graph_cli.graph_query_command(sparql, store=location, infer=True) == 2
        assert "--infer" in capsys.readouterr().out

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

    labels = "SELECT ?l WHERE { ?n a rdm:DesignInput ; rdfs:label ?l } ORDER BY ?l"
    try:
        with verification_step("Serve: the union default graph, standard prefixes, CORS open"):
            with get(labels) as response:
                body = response.read().decode()
                cors = response.headers.get("Access-Control-Allow-Origin")
            assert body.split() == ["l", "DI-1"] and cors == "*"  # the v2 build: DI-2 removed
            with get("ASK { ?s ?p ?o }", "application/sparql-results+json") as response:
                assert json.loads(response.read())["boolean"] is True
        with verification_step("Serve refuses an update from another origin, and the data stays"):
            update = urllib.request.Request(
                f"{base}/update", method="POST", data=urllib.parse.urlencode({"update": "CLEAR ALL"}).encode(),
                headers={"Origin": "https://elsewhere.example", "Content-Type": "application/x-www-form-urlencoded"})
            assert refused(update) == 403
            with get(labels) as response:
                assert response.read().decode().split() == ["l", "DI-1"]
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
                assert response.read().decode().split() == ["l", "DI-1", "DI-2"]
    finally:
        server.shutdown()
        server.server_close()

    with verification_step("A missing store is refused, not created"):
        assert graph_cli.graph_serve_command(store=tmp_path / "nope") == 2


@allure.story("DI-85")
@allure.label("component", "Projection")
def test_the_graph_stands_on_one_rdf_library_in_python(tmp_path: Path) -> None:
    """DI-85: the record's RDF is projected, stored, queried and validated through
    one RDF library written in Python; the built record is byte-identical wherever
    it is built, and no graph command needs native code."""
    import sys

    from rdm.graph.validate import validate

    dhf, results = _record(tmp_path)
    quads = project(dhf, results)
    with verification_step("the graph imports no native code of its own: every module it loads is Python"):
        import rdm.graph.agent  # noqa: F401 (every graph module loaded)
        import rdm.graph.endpoint  # noqa: F401
        import rdm.graph.explorer  # noqa: F401
        validate(quads)
        native = sorted(name for name, module in sys.modules.items()
                        if name.split(".")[0] in ("rdm", "rdflib", "pyshacl", "pyoxigraph", "owlrl", "pyparsing")
                        and str(getattr(module, "__file__", "")).endswith((".so", ".pyd", ".dylib")))
        assert native == [] and "pyoxigraph" not in sys.modules

    with verification_step("the built record is sorted N-Quads, byte-identical across builds, and read back whole"):
        text = nquads(quads)
        assert text == nquads(project(dhf, results)) and text.splitlines() == sorted(text.splitlines())
        assert set(ox.parse(text.encode(), format=ox.RdfFormat.N_QUADS)) == set(quads)
        store_dir = tmp_path / "store"
        assert build_store(store_dir, quads) == len(set(quads))
        assert (store_dir / "store.nq").read_text(encoding="utf-8") == text
        assert set(ox.Store.read_only(str(store_dir))) == set(quads)

    with verification_step("queries answer in every format the command line and the endpoint write"):
        store = _store(quads)
        select = PREFIXES + "SELECT ?id WHERE { ?i a rdm:DesignInput ; dcterms:identifier ?id } ORDER BY ?id"
        rows = store.query(select, use_default_graph_as_union=True)
        ids = [str(row["id"].value) for row in rows]
        assert ids == ["DI-1", "DI-2"]
        as_json = json.loads(rows.serialize(format=ox.QueryResultsFormat.JSON))
        assert [b["id"]["value"] for b in as_json["results"]["bindings"]] == ids
        assert rows.serialize(format=ox.QueryResultsFormat.TSV).decode() == '?id\n"DI-1"\n"DI-2"\n'
        assert rows.serialize(format=ox.QueryResultsFormat.CSV).decode() == "id\r\nDI-1\r\nDI-2\r\n"
        xml = rows.serialize(format=ox.QueryResultsFormat.XML).decode()
        assert xml.count("<result>") == 2 and "<literal>DI-1</literal>" in xml
        assert bool(store.query(PREFIXES + "ASK { ?i a rdm:DesignInput }", use_default_graph_as_union=True))
        built = store.query(PREFIXES + "CONSTRUCT { ?i a rdm:DesignInput } WHERE { ?i a rdm:DesignInput }",
                            use_default_graph_as_union=True)
        assert ox.serialize(built, format=ox.RdfFormat.N_TRIPLES).decode().count("\n") == 2

    with verification_step("what is not a query, or not RDF, is refused as a syntax error, as before"):
        with pytest.raises(SyntaxError):
            store.query("this is not sparql")
        with pytest.raises(SyntaxError):
            list(ox.parse(b"<a> <b> .", format=ox.RdfFormat.TURTLE))
