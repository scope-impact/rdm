"""MkDocs build hook: traceability from the graph, for RDM and its worked example.

Before the build, run each record's acceptance tests into its Allure results,
so the evidence is live. Then project each record into its RDF graph and
answer a handful of SPARQL queries: user needs, risks, design inputs, tagged
tests and their runs, C3 components in their bounded contexts, the declared
C4 relationships, the code imports and the files tests exercise. The answers
go to ``assets/<name>.json``, which the page of the same name draws with
``docs/javascripts/traceability-map.js``. Nothing but the graph feeds a map.

Best-effort: a test run that fails still leaves its results; without the
``graph`` extra the data is ``{"error": why}`` and the page says so, so
``mkdocs build --strict`` never breaks.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from mkdocs.structure.files import File

ROOT = Path(__file__).resolve().parents[2]
EXAMPLE = ROOT / "examples" / "github-document-control"
# page name -> the project whose acceptance tests run, and its DHF
MAPS = {"traceability-map": ROOT, "example-traceability-map": EXAMPLE}


def graph_data(dhf: Path, results: Path) -> dict:
    """The map's data, every value from a SPARQL query over the projected graph."""
    import pyoxigraph as ox

    from rdm.graph.ns import with_prefixes
    from rdm.graph.project import project

    store = ox.Store()
    store.extend(project(dhf, results if results.is_dir() else None))

    def q(sparql: str) -> list[dict]:
        r = store.query(with_prefixes(sparql), use_default_graph_as_union=True)
        names = [v.value for v in r.variables]
        return [{n: (row[n].value if row[n] is not None else None) for n in names} for row in r]

    nodes: dict[str, dict] = {}
    edges: set[tuple[str, str, str]] = set()

    def node(iri, kind, label, **extra):
        nodes.setdefault(iri, {"id": iri, "kind": kind, "label": label, **extra})

    for r in q("SELECT ?n ?id ?t WHERE { ?n a rdm:UserNeed ; dcterms:identifier ?id OPTIONAL { ?n rdm:text ?t } }"):
        node(r["n"], "need", r["id"], text=r["t"] or "")
    for r in q("SELECT ?n ?id ?t ?ctx WHERE { ?n a rdm:DesignInput ; dcterms:identifier ?id "
               "OPTIONAL { ?n rdm:text ?t } OPTIONAL { ?n rdm:ownedBy ?ctx } }"):
        node(r["n"], "input", r["id"], text=r["t"] or "", owner=r["ctx"])
    for r in q("SELECT ?n ?l WHERE { ?n a rdm:BoundedContext OPTIONAL { ?n rdfs:label ?l } }"):
        node(r["n"], "context", r["l"] or r["n"].split("context/")[-1])
    for r in q("""SELECT ?n ?id ?h ?lvl ?dec ?cat WHERE { ?n a rdm:Risk ; dcterms:identifier ?id
                  OPTIONAL { ?n rdm:hazard ?h } OPTIONAL { ?n rdm:level ?lvl }
                  OPTIONAL { ?n rdm:residualDecision ?dec } OPTIONAL { ?n rdm:category ?cat } }"""):
        node(r["n"], "risk", r["id"], text=r["h"] or "", level=r["lvl"], decision=r["dec"], category=r["cat"])
    for r in q("""SELECT ?n ?l ?d ?ctx ?kl ?code WHERE { ?n a rdm:Component ; rdfs:label ?l
                  OPTIONAL { ?n dcterms:description ?d } OPTIONAL { ?n rdm:inContext ?ctx }
                  OPTIONAL { ?n rdm:containedIn ?k . ?k rdfs:label ?kl } OPTIONAL { ?n rdm:code ?code } }"""):
        node(r["n"], "component", r["l"], text=r["d"] or "", context=r["ctx"], container=r["kl"], code=r["code"])
    for r in q("""SELECT ?t ?name ?d ?status ?c WHERE {
                  ?r a rdm:TestRun ; rdm:runOf ?t ; rdm:exercises ?d ; rdm:status ?status . ?t rdfs:label ?name
                  OPTIONAL { ?r rdm:exercisesOutput/rdm:inComponent ?c } }"""):
        node(r["t"], "test", r["name"], status=r["status"])
        edges.add((r["t"], r["d"], "verifies"))
        if r["c"]:
            edges.add((r["t"], r["c"], "exercises"))
    for rel, sparql in (("traces to", "SELECT ?s ?o WHERE { ?s a rdm:DesignInput ; rdm:tracesTo ?o }"),
                        ("owns", "SELECT ?s ?o WHERE { ?o a rdm:DesignInput ; rdm:ownedBy ?s }"),
                        ("realises", "SELECT ?s ?o WHERE { ?s a rdm:BoundedContext ; rdm:realises ?o }"),
                        ("controlled by", "SELECT ?s ?o WHERE { ?s a rdm:Risk ; rdm:controlledBy ?o }"),
                        ("in context", "SELECT ?s ?o WHERE { ?s a rdm:Component ; rdm:inContext ?o }")):
        edges.update((r["s"], r["o"], rel) for r in q(sparql))
    elements = {r["e"]: {"label": r["l"], "kind": r["k"].split("#")[-1]} for r in q(
        "SELECT ?e ?l ?k WHERE { ?e a ?k ; rdfs:label ?l . "
        "FILTER(?k IN (rdm:Person, rdm:SoftwareSystem, rdm:Container, rdm:Component)) }")}
    files: dict[str, list[str]] = {}
    for r in q("SELECT DISTINCT ?c ?p WHERE { ?f rdm:inComponent ?c ; rdm:path ?p }"):
        files.setdefault(r["c"], []).append(r["p"])
    commit = q("SELECT ?sha WHERE { ?r a rdm:TestRun ; rdm:testedAt ?c . ?c rdm:sha ?sha } LIMIT 1")
    return {
        "nodes": list(nodes.values()),
        "edges": [{"s": s, "t": t, "rel": k} for s, t, k in sorted(edges) if s in nodes and t in nodes],
        "commit": commit[0]["sha"] if commit else None,
        "elements": elements,
        "rels": q("SELECT ?s ?t ?d ?tech WHERE { ?r a rdm:Relationship ; rdm:source ?s ; rdm:target ?t "
                  "OPTIONAL { ?r dcterms:description ?d } OPTIONAL { ?r rdm:technology ?tech } }"),
        "deps": q("SELECT ?s ?t ?i ?b WHERE { ?d a rdm:Dependency ; rdm:source ?s ; rdm:target ?t "
                  "OPTIONAL { ?d rdm:imports ?i } OPTIONAL { ?d rdm:importedBy ?b } }"),
        "files": files,
    }


def on_pre_build(config) -> None:
    for project in MAPS.values():
        try:
            subprocess.run([sys.executable, "-m", "pytest", "tests/acceptance", "-q",
                            "--clean-alluredir", "--alluredir", "dhf/allure-results"],
                           cwd=project, capture_output=True, timeout=600)
        except Exception:
            pass  # the map then shows the results the record already has, or none


def on_files(files, config):
    for name, project in MAPS.items():
        try:
            data = graph_data(project / "dhf", project / "dhf" / "allure-results")
        except ImportError as error:
            data = {"error": f"the graph extra is not installed ({error.name})"}
        except Exception as error:  # a build never breaks on a map
            data = {"error": f"{type(error).__name__}: {error}"}
        files.append(File.generated(config, f"assets/{name}.json", content=json.dumps(data)))
    return files
