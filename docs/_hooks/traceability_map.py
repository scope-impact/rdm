"""MkDocs build hook: the interactive traceability map, generated from the graph.

After the evidence hook has run the acceptance suite, project the record into
its RDF graph and answer a handful of SPARQL queries: user needs, risks, design
inputs, tagged tests and their runs, C3 components in their bounded contexts,
the declared C4 relationships, the code imports and the files tests exercise.
The answers are embedded in ``traceability_map.html`` with the docs theme
(``docs/stylesheets/rdm-theme.css``) and written to
``assets/traceability-map.html``, which ``traceability-map.md`` shows. Nothing
but the graph feeds the map.

Best-effort: without the ``graph`` extra the page is a short notice instead,
so ``mkdocs build --strict`` never breaks.
"""

from __future__ import annotations

import html
import json
from pathlib import Path

from mkdocs.structure.files import File

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path(__file__).with_name("traceability_map.html")
THEME = ROOT / "docs" / "stylesheets" / "rdm-theme.css"
OUTPUT = "assets/traceability-map.html"
LIBRARIES = ("cytoscape@3.34.3/dist/cytoscape.min.js", "elkjs@0.12.0/lib/elk.bundled.js",
             "cytoscape-elk@2.3.0/dist/cytoscape-elk.js")


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


def render(data: dict) -> str:
    """The map page: the theme's tokens, then the template, the libraries and the data."""
    page = TEMPLATE.read_text(encoding="utf-8")
    page = page.replace("<style>", "<style>\n" + THEME.read_text(encoding="utf-8") + "\n", 1)
    scripts = "".join(f'<script src="https://cdn.jsdelivr.net/npm/{lib}"></script>\n' for lib in LIBRARIES)
    return page.replace("__SCRIPTS__", scripts).replace("__DATA__", json.dumps(data).replace("</", "<\\/"))


def notice(reason: str) -> str:
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8"><title>RDM traceability map</title></head>'
            '<body style="font-family:system-ui;padding:24px">'
            f"The traceability map was not generated: {html.escape(reason)}</body></html>")


def on_files(files, config):
    try:
        content = render(graph_data(ROOT / "dhf", ROOT / "dhf" / "allure-results"))
    except ImportError as error:
        content = notice(f"the graph extra is not installed ({error.name})")
    except Exception as error:  # a build never breaks on the map
        content = notice(f"{type(error).__name__}: {error}")
    files.append(File.generated(config, OUTPUT, content=content))
    return files
