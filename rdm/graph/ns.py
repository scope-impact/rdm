"""
The namespaces of RDM's graph, declared once: the IRIs the projection writes
and the prefixes every query may use without declaring them.
"""

from __future__ import annotations

import re

# A query's own PREFIX for the same name wins (with_prefixes).
PREFIXES = {
    "rdm": "https://github.com/scope-impact/rdm/ns#",
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
    "xsd": "http://www.w3.org/2001/XMLSchema#",
    "dcterms": "http://purl.org/dc/terms/",
    "prov": "http://www.w3.org/ns/prov#",
    "oslc_rm": "http://open-services.net/ns/rm#",
    "skos": "http://www.w3.org/2004/02/skos/core#",
}

RDM, RDF, RDFS, XSD = PREFIXES["rdm"], PREFIXES["rdf"], PREFIXES["rdfs"], PREFIXES["xsd"]
DCTERMS, PROV, SKOS = PREFIXES["dcterms"], PREFIXES["prov"], PREFIXES["skos"]
SHACL = "http://www.w3.org/ns/shacl#"


def with_prefixes(sparql: str) -> str:
    """Prepend the standard PREFIX lines the query does not declare itself."""
    declared = {m.group(1) for m in re.finditer(r"(?im)^\s*PREFIX\s+([A-Za-z_][\w-]*)?:", sparql)}
    head = "".join(f"PREFIX {name}: <{iri}>\n" for name, iri in PREFIXES.items() if name not in declared)
    return head + sparql
