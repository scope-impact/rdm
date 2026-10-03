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


_UPDATE = re.compile(r"(?i)\b(INSERT|DELETE|LOAD|CLEAR|CREATE|DROP|COPY|MOVE|ADD)\b")


def _keyword(word: str) -> re.Pattern:
    """``word`` as a SPARQL keyword: not a variable (``?SERVICE``), nor part of
    a prefixed name (``SERVICE:p``, ``ex:SERVICE``, ``PREFIX SERVICE:``)."""
    return re.compile(rf"(?i)(?<![\w?$:]){word}\b(?!\s*:)")


_SERVICE, _FROM = _keyword("SERVICE"), _keyword("FROM")
# String literals, IRIs and comments, removed before looking for SERVICE so a
# literal or an IRI that merely contains the word is not refused. A comment
# ends at either line break, as SPARQL's does.
_NOT_KEYWORDS = re.compile(
    r'"""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\''               # long literals
    r'|"(?:[^"\\\r\n]|\\.)*"|\'(?:[^\'\\\r\n]|\\.)*\''  # short literals
    r'|<[^<>\s]*>|#[^\r\n]*')                                # IRIs, comments


class ReadOnlyError(ValueError):
    """Raised for anything that is not a read-only, local SPARQL query."""


def read_only_query(store, sparql: str):
    """Answer a SPARQL query over ``store``'s named graphs as one union, with
    the standard prefixes; refuse SPARQL Update, a federated SERVICE call and
    anything that is not a query, so nothing changes the store or reaches the
    network (DI-36, DI-42); and FROM, which the union would silently ignore."""
    keywords = _NOT_KEYWORDS.sub(" ", sparql)
    if _SERVICE.search(keywords):
        raise ReadOnlyError("SERVICE is not accepted: the graph does not reach the network.")
    if _FROM.search(keywords):
        raise ReadOnlyError("FROM is not accepted: the default graph is the union of the named graphs, so it "
                            "would be ignored. Name a graph with GRAPH <…> { … } instead.")
    try:
        return store.query(with_prefixes(sparql), use_default_graph_as_union=True)
    except SyntaxError as error:
        if _UPDATE.search(sparql):
            raise ReadOnlyError("SPARQL Update is not accepted: the graph is read-only. "
                                "Change the record and open a pull request.") from error
        raise ReadOnlyError(f"not a SPARQL query (SELECT, ASK, CONSTRUCT, DESCRIBE): {error}") from error
