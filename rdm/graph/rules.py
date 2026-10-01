"""
Derived relations (DI-62).

The graph stores only what the record states. A relation that follows from
others is declared in the vocabulary (``ontology.ttl``) as an ``rdm:Rule``
with a SPARQL CONSTRUCT, so a consumer — an agent writing its own SPARQL —
can learn the rule instead of finding nothing. ``infer`` runs the rules and
returns their results in one named graph, kept apart from the record's.
"""

from __future__ import annotations

import pyoxigraph as ox

from rdm.graph.project import NS, ONTOLOGY_FILE

_PREFIXES = f"PREFIX rdm: <{NS}>\nPREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"


def rules() -> list[dict]:
    """Every rule the vocabulary declares: its IRI, label, comment, the
    relation it derives and its CONSTRUCT."""
    store = ox.Store()
    store.load(path=str(ONTOLOGY_FILE), format=ox.RdfFormat.TURTLE)
    rows = store.query(_PREFIXES + """SELECT ?rule ?label ?comment ?derives ?construct WHERE {
        ?rule a rdm:Rule ; rdfs:label ?label ; rdm:derives ?derives ; rdm:construct ?construct .
        OPTIONAL { ?rule rdfs:comment ?comment } } ORDER BY ?rule""")
    return [{"rule": r["rule"].value, "label": r["label"].value, "derives": r["derives"].value,
             "comment": r["comment"].value if r["comment"] is not None else "",
             "construct": r["construct"].value} for r in rows]


def infer(quads: list[ox.Quad], graph: ox.NamedNode) -> list[ox.Quad]:
    """The facts the rules derive from ``quads`` (read as one union), each in
    ``graph`` — never in the graphs they were derived from."""
    store = ox.Store()
    store.extend(quads)
    derived: list[ox.Quad] = []
    for rule in rules():
        for triple in store.query(_PREFIXES + rule["construct"], use_default_graph_as_union=True):
            derived.append(ox.Quad(triple.subject, triple.predicate, triple.object, graph))
    return derived
