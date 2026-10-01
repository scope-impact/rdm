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

_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
DEFAULT_ENDPOINT = "http://localhost:7878"


def _local(iri: str) -> str:
    return iri.rsplit("#", 1)[-1].rsplit("/", 1)[-1]


def explorer_graph(quads: list[ox.Quad], endpoint: str = DEFAULT_ENDPOINT,
                   exclude: list[str] | None = None) -> dict:
    """The Graph Explorer file for ``quads``: every typed instance node (its
    IRI is a ``urn:``, so the vocabulary is left out) and every link between two
    of them except ``rdf:type``; ``exclude`` names classes (by local name,
    e.g. ``TestRun``) whose nodes and links are left out."""
    excluded = set(exclude or [])
    types: dict[str, set[str]] = {}
    for q in quads:
        if q.predicate.value == _TYPE and isinstance(q.subject, ox.NamedNode) and q.subject.value.startswith("urn:"):
            types.setdefault(q.subject.value, set()).add(_local(q.object.value))
    nodes = {n for n, kinds in types.items() if not kinds & excluded}
    edges = {
        f"{q.subject.value}-[{q.predicate.value}]->{q.object.value}"
        for q in quads
        if q.predicate.value != _TYPE and isinstance(q.object, ox.NamedNode)
        and q.subject.value in nodes and q.object.value in nodes
    }
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
