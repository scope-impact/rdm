"""
Checklists and reference tags in the graph (DI-37).

Checklists are **data**: a standard is a ``skos:ConceptScheme``, an item a
``rdm:Clause`` (a ``skos:Concept``), a checklist a ``rdm:Checklist`` (a
``skos:Collection``). A new standard or checklist is a new file — a ``.txt``
in ``rdm gap``'s format or an RDF file — never a code or vocabulary change.

Clause, standard and checklist IRIs are project-independent
(``urn:rdm:clause:62304:5.6.2``), so graphs from several repositories share
one node per clause and a query can ask which projects reference it.

``rdm gap``'s own reader (includes, built-in names) and key matcher
(descendant keys cover their parent; a longer sibling never matches) are
reused, so a clause no document references here is exactly an item
``rdm gap`` reports missing.
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from urllib.parse import quote

import pyoxigraph as ox

from rdm.graph.ns import DCTERMS, SKOS

from rdm.compliance import gaps

_SKOS, _DCT = SKOS, DCTERMS
_RDF_SUFFIXES = {
    ".ttl": ox.RdfFormat.TURTLE, ".nt": ox.RdfFormat.N_TRIPLES, ".nq": ox.RdfFormat.N_QUADS,
    ".trig": ox.RdfFormat.TRIG, ".jsonld": ox.RdfFormat.JSON_LD, ".rdf": ox.RdfFormat.RDF_XML,
    ".owl": ox.RdfFormat.RDF_XML,
}


def _iri(kind: str, local: str) -> ox.NamedNode:
    return ox.NamedNode(f"urn:rdm:{kind}:" + quote(local, safe=":/-._~@"))


def clause_node(key: str) -> ox.NamedNode:
    return _iri("clause", key)


def scheme_of(key: str) -> str:
    """The standard a key belongs to: its prefix (``62304:5.6.2`` -> ``62304``)."""
    return key.split(":", 1)[0] if ":" in key else "local"


def _base(key: str) -> tuple[str, str | None]:
    """``62304:5.2.2.j@2015`` -> (``62304:5.2.2.j``, ``2015``)."""
    base, _, edition = key.partition("@")
    return base, (edition or None)


def _parents(key: str):
    """Dotted ancestors, nearest first: ``62304:5.6.2.a`` -> 62304:5.6.2, 62304:5.6, 62304:5."""
    base, _ = _base(key)
    if ":" not in base:
        return
    scheme, path = base.split(":", 1)
    parts = path.split(".")
    for end in range(len(parts) - 1, 0, -1):
        yield f"{scheme}:{'.'.join(parts[:end])}"


def resolve(spec: str) -> Path:
    """A built-in checklist name, or a path to a checklist file."""
    builtins = gaps.builtin_checklists()
    if spec in builtins:
        return Path(builtins[spec])
    path = Path(spec)
    if not path.exists():
        raise FileNotFoundError(f"no built-in checklist or file named {spec!r} "
                                f"(built-ins: {', '.join(sorted(builtins))})")
    return path


def _read_text_checklist(path: Path) -> tuple[list[dict], list[Path]]:
    """One checklist file's own items and the files it includes (not flattened)."""
    builtins = gaps.builtin_checklists()
    directory = os.path.dirname(os.path.realpath(path))
    items, includes = [], []
    for entry in gaps.parse_checklist(path.read_text(encoding="utf-8"), directory):
        if "include" in entry:
            includes.append(Path(gaps.include_path(entry["include"], builtins, entry["path"])))
        else:
            items.append(entry)
    return items, includes


def checklist_quads(specs: list[str], graph: ox.NamedNode) -> tuple[list[ox.Quad], dict[str, ox.NamedNode]]:
    """Quads for the requested checklists (and everything they include), plus
    every clause key they declare, mapped to that clause's node — the
    generated IRI for a text checklist, the file's own subject for an RDF one."""
    quads: list[ox.Quad] = []
    keys: set[str] = set()

    def add(s, p, o):
        quads.append(ox.Quad(s, p, ox.Literal(o) if isinstance(o, str) else o, graph))

    from rdm.graph.project import LABEL, TYPE, rdm

    pending = [resolve(spec) for spec in specs]
    seen: set[Path] = set()
    while pending:
        path = pending.pop(0).resolve()
        if path in seen:
            continue
        seen.add(path)
        if path.suffix.lower() in _RDF_SUFFIXES:  # native RDF checklist: loaded as-is
            for q in ox.parse(path=str(path), format=_RDF_SUFFIXES[path.suffix.lower()]):
                quads.append(ox.Quad(q.subject, q.predicate, q.object, graph))
            continue
        items, includes = _read_text_checklist(path)
        checklist = _iri("checklist", path.stem)
        add(checklist, TYPE, rdm("Checklist"))
        add(checklist, LABEL, path.stem)
        add(checklist, ox.NamedNode(_DCT + "title"), path.stem)
        for item in items:
            key = item["reference"]
            keys.add(key)
            add(checklist, ox.NamedNode(_SKOS + "member"), clause_node(key))
            clause = clause_node(key)
            add(clause, TYPE, rdm("Clause"))
            add(clause, LABEL, key)
            add(clause, ox.NamedNode(_SKOS + "notation"), key)
            if item.get("description"):
                add(clause, ox.NamedNode(_SKOS + "definition"), item["description"])
            scheme = _iri("standard", scheme_of(key))
            add(clause, ox.NamedNode(_SKOS + "inScheme"), scheme)
            add(scheme, TYPE, ox.NamedNode(_SKOS + "ConceptScheme"))
            add(scheme, LABEL, scheme_of(key))
            edition = _base(key)[1]
            if edition:
                add(clause, rdm("edition"), edition)
        for include in includes:
            add(checklist, rdm("includes"), _iri("checklist", include.stem))
            pending.append(include)

    # Parent clauses: the nearest dotted ancestor that is itself a listed clause.
    for key in sorted(keys):
        for parent in _parents(key):
            if parent in keys:
                add(clause_node(key), ox.NamedNode(_SKOS + "broader"), clause_node(parent))
                break
    _label_unlabelled(quads, graph)
    # Keys declared by native RDF checklists take part in reference matching too.
    clauses = {key: clause_node(key) for key in keys}
    notation = ox.NamedNode(_SKOS + "notation")
    for q in quads:
        if q.predicate == notation and isinstance(q.subject, ox.NamedNode):
            clauses.setdefault(q.object.value, q.subject)
    return quads, clauses


def _iri_tail(iri: str) -> str:
    """A readable name from an IRI: the id after ``urn:rdm:<kind>:`` (keeping
    the key's own colons, ``urn:rdm:clause:BAD:1`` -> ``BAD:1``), else the
    last path or fragment segment."""
    match = re.match(r"^urn:rdm:[^:]+:(.+)$", iri)
    if match:
        return match.group(1)
    return re.split(r"[/#]", iri.rstrip("/#"))[-1] or iri


def _label_unlabelled(quads: list[ox.Quad], graph: ox.NamedNode) -> None:
    """Give every typed checklist node an ``rdfs:label`` (DI-35's rule), so an
    RDF-authored checklist is as searchable as a text one: its key, preferred
    label or title, else the last segment of its IRI."""
    from rdm.graph.project import LABEL, TYPE

    names = (_SKOS + "notation", _SKOS + "prefLabel", _DCT + "title")
    by_subject: dict = {}
    for q in quads:
        by_subject.setdefault(q.subject, {}).setdefault(q.predicate.value, q.object)
    for subject, props in by_subject.items():
        if TYPE.value not in props or LABEL.value in props or not isinstance(subject, ox.NamedNode):
            continue
        name = next((props[n].value for n in names if n in props), _iri_tail(subject.value))
        quads.append(ox.Quad(subject, LABEL, ox.Literal(name), graph))


def reference_quads(documents: list[dict], doc_node, clauses: dict[str, ox.NamedNode],
                    graph: ox.NamedNode) -> list[ox.Quad]:
    """``dcterms:references`` from each controlled document to the clauses its
    ``[[…]]`` tags name, matched exactly as ``rdm gap`` matches them."""
    references = ox.NamedNode(_DCT + "references")
    quads = []
    for doc in documents:
        text = Path(doc["file"]).read_text(encoding="utf-8", errors="ignore")
        for key in sorted(gaps.find_keys(text, set(clauses))):
            quads.append(ox.Quad(doc_node(doc["id"]), references, clauses[key], graph))
    return quads
