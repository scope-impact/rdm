"""
The whole record as an AWS Graph Explorer graph file (DI-39).

Graph Explorer's *Load graph from file* takes a small JSON envelope — node
IRIs, edges written ``subject-[predicate]->object``, and the endpoint to fetch
their details from. Writing it from the projection opens the full
traceability graph in one step instead of a hundred manual expansions.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone

import pyoxigraph as ox

from rdm.graph.allure import GRAPH as EXECUTIONS_GRAPH
from rdm.graph.ns import RDF

_TYPE = RDF + "type"
DEFAULT_ENDPOINT = "http://localhost:7878"
_EXECUTIONS = "graph/" + EXECUTIONS_GRAPH  # the test-run results


def _local(iri: str) -> str:
    return iri.rsplit("#", 1)[-1].rsplit("/", 1)[-1]


def _reachable(start: set[str], links) -> set[str]:
    """Nodes connected to ``start`` by ``links`` in either direction."""
    neighbours: dict[str, set[str]] = {}
    for s, _, o in links:
        neighbours.setdefault(s, set()).add(o)
        neighbours.setdefault(o, set()).add(s)
    seen, todo = set(start), list(start)
    while todo:
        for n in neighbours.get(todo.pop(), ()):
            if n not in seen:
                seen.add(n)
                todo.append(n)
    return seen


def explorer_graph(quads: list[ox.Quad], endpoint: str = DEFAULT_ENDPOINT,
                   exclude: list[str] | None = None) -> dict:
    """The Graph Explorer file for ``quads``: every typed instance node (its
    IRI is a ``urn:``, so the vocabulary is left out) and every link between two
    of them except ``rdf:type``; ``exclude`` names classes (by local name,
    e.g. ``TestRun``) whose nodes and links are left out, together with the
    nodes that hang only from them: those left with no path to a node outside
    the test-run results (a run's steps, labels, attachments, fixtures...)."""
    excluded = set(exclude or [])
    types: dict[str, set[str]] = {}
    anchors: set[str] = set()  # typed outside the test-run results
    for q in quads:
        if q.predicate.value == _TYPE and isinstance(q.subject, ox.NamedNode) and q.subject.value.startswith("urn:"):
            types.setdefault(q.subject.value, set()).add(_local(q.object.value))
            if not str(getattr(q.graph_name, "value", "")).endswith(_EXECUTIONS):
                anchors.add(q.subject.value)
    nodes = {n for n, kinds in types.items() if not kinds & excluded}
    links = [(q.subject.value, q.predicate.value, q.object.value) for q in quads
             if q.predicate.value != _TYPE and isinstance(q.object, ox.NamedNode)
             and q.subject.value in nodes and q.object.value in nodes]
    if excluded:
        nodes = _reachable(anchors & nodes, links)
    edges = {f"{s}-[{p}]->{o}" for s, p, o in links if s in nodes and o in nodes}
    return {
        "meta": {
            "kind": "graph-export",
            "version": "1.0",
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "source": "rdm",
            "sourceVersion": "rdm graph explorer-file",
        },
        "data": {
            "connection": {"dbUrl": endpoint, "queryEngine": "sparql"},
            "vertices": sorted(nodes),
            "edges": sorted(edges),
        },
    }


def write_explorer_file(path, quads, endpoint: str = DEFAULT_ENDPOINT, exclude=None) -> dict:
    graph = explorer_graph(quads, endpoint, exclude)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(graph, handle, indent=1)
        handle.write("\n")
    return graph
