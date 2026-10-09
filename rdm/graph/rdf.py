"""
The graph's RDF: terms, quads, parsing and writing, SPARQL and the store, on
one RDF library written in Python (rdflib), so the graph runs the same on the
command line and inside the component (DI-85).

Terms print in N-Triples form (the projection sorts quads by ``str``);
N-Triples and N-Quads are written one statement per line with the standard
escapes, so the built record is byte-identical wherever it is built; SPARQL
is answered by rdflib over the named graphs as one union; a store on disk is
one N-Quads file in its directory, replaced on each build.
"""

from __future__ import annotations

import csv
import enum
import io
import json
import re
from html import escape
from pathlib import Path

import rdflib
from pyparsing import ParseException
from rdflib.term import BNode as _RBNode
from rdflib.term import Literal as _RLiteral
from rdflib.term import URIRef as _RURIRef

def _keep_lexical() -> None:
    """Keep each literal's lexical form, as a store does. Set before every
    parse and query, never once at import: pyshacl turns rdflib's
    normalisation back on after each validation, and a normalised literal
    ("...Z" read back as "...+00:00") is a different term."""
    rdflib.NORMALIZE_LITERALS = False


_keep_lexical()

_XSD_STRING = "http://www.w3.org/2001/XMLSchema#string"
_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
_STORE_FILE = "store.nq"


def _escape(text: str) -> str:
    """A string with N-Triples' escapes."""
    out = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif ord(ch) < 0x20 or ord(ch) == 0x7F:
            out.append(f"\\u{ord(ch):04X}")
        else:
            out.append(ch)
    return "".join(out)


class NamedNode:
    __slots__ = ("value",)

    def __init__(self, value: str):
        self.value = str(value)

    def __str__(self):
        return f"<{self.value}>"

    def __repr__(self):
        return f"<NamedNode value={self.value}>"

    def __eq__(self, other):
        return isinstance(other, NamedNode) and other.value == self.value

    def __hash__(self):
        return hash(("NamedNode", self.value))


class BlankNode:
    __slots__ = ("value",)
    _next = 0

    def __init__(self, value: str | None = None):
        if value is None:
            BlankNode._next += 1
            value = f"b{BlankNode._next}"
        self.value = str(value)

    def __str__(self):
        return f"_:{self.value}"

    def __eq__(self, other):
        return isinstance(other, BlankNode) and other.value == self.value

    def __hash__(self):
        return hash(("BlankNode", self.value))


class Literal:
    __slots__ = ("value", "datatype", "language")

    def __init__(self, value, *, datatype: NamedNode | None = None, language: str | None = None):
        self.value = str(value)
        self.language = language.lower() if language else None
        self.datatype = NamedNode(_LANG_STRING) if language else (datatype or NamedNode(_XSD_STRING))

    def __str__(self):
        if self.language:
            return f'"{_escape(self.value)}"@{self.language}'
        if self.datatype.value == _XSD_STRING:
            return f'"{_escape(self.value)}"'
        return f'"{_escape(self.value)}"^^{self.datatype}'

    def __repr__(self):
        return f"<Literal {self}>"

    def __eq__(self, other):
        return isinstance(other, Literal) and (other.value, other.datatype, other.language) == \
            (self.value, self.datatype, self.language)

    def __hash__(self):
        return hash(("Literal", self.value, self.datatype.value, self.language))


class DefaultGraph:
    def __str__(self):
        return "DEFAULT"

    def __eq__(self, other):
        return isinstance(other, DefaultGraph)

    def __hash__(self):
        return hash("DefaultGraph")


class Variable:
    __slots__ = ("value",)

    def __init__(self, value: str):
        self.value = str(value)

    def __str__(self):
        return f"?{self.value}"

    def __eq__(self, other):
        return isinstance(other, Variable) and other.value == self.value

    def __hash__(self):
        return hash(("Variable", self.value))


class Triple:
    __slots__ = ("subject", "predicate", "object")

    def __init__(self, subject, predicate, object):  # noqa: A002
        self.subject, self.predicate, self.object = subject, predicate, object

    def __str__(self):
        return f"{self.subject} {self.predicate} {self.object}"

    def __eq__(self, other):
        return isinstance(other, Triple) and (other.subject, other.predicate, other.object) == \
            (self.subject, self.predicate, self.object)

    def __hash__(self):
        return hash((self.subject, self.predicate, self.object))

    def __iter__(self):
        return iter((self.subject, self.predicate, self.object))


class Quad:
    __slots__ = ("subject", "predicate", "object", "graph_name")

    def __init__(self, subject, predicate, object, graph_name=None):  # noqa: A002
        self.subject, self.predicate, self.object = subject, predicate, object
        self.graph_name = DefaultGraph() if graph_name is None else graph_name

    @property
    def triple(self) -> Triple:
        return Triple(self.subject, self.predicate, self.object)

    def __str__(self):
        head = f"{self.subject} {self.predicate} {self.object}"
        return head if isinstance(self.graph_name, DefaultGraph) else f"{head} {self.graph_name}"

    def __repr__(self):
        return f"<Quad {self}>"

    def __eq__(self, other):
        return isinstance(other, Quad) and (other.subject, other.predicate, other.object, other.graph_name) == \
            (self.subject, self.predicate, self.object, self.graph_name)

    def __hash__(self):
        return hash((self.subject, self.predicate, self.object, self.graph_name))

    def __iter__(self):
        return iter((self.subject, self.predicate, self.object, self.graph_name))


class RdfFormat(enum.Enum):
    N_TRIPLES = "nt"
    N_QUADS = "nquads"
    TURTLE = "turtle"
    TRIG = "trig"
    RDF_XML = "xml"
    JSON_LD = "json-ld"
    N3 = "n3"


class QueryResultsFormat(enum.Enum):
    JSON = "json"
    XML = "xml"
    CSV = "csv"
    TSV = "tsv"


# rdflib terms <-> these terms

def _from_rdflib(term):
    if term is None:
        return None
    if isinstance(term, _RURIRef):
        return NamedNode(str(term))
    if isinstance(term, _RBNode):
        return BlankNode(str(term))
    if isinstance(term, _RLiteral):
        return Literal(str(term), datatype=NamedNode(str(term.datatype)) if term.datatype else None,
                       language=term.language)
    raise TypeError(f"unexpected term: {term!r}")


def _to_rdflib(term):
    if isinstance(term, NamedNode):
        return _RURIRef(term.value)
    if isinstance(term, BlankNode):
        return _RBNode(term.value)
    if isinstance(term, Literal):
        if term.language:
            return _RLiteral(term.value, lang=term.language)
        if term.datatype.value == _XSD_STRING:
            return _RLiteral(term.value)  # RDF 1.1: a simple literal is an xsd:string
        return _RLiteral(term.value, datatype=_RURIRef(term.datatype.value), normalize=False)
    raise TypeError(f"unexpected term: {term!r}")


def _quads_of(dataset: rdflib.Dataset):
    for s, p, o, g in dataset.quads((None, None, None, None)):
        graph = None if g is None or g == rdflib.graph.DATASET_DEFAULT_GRAPH_ID else _from_rdflib(g)
        yield Quad(_from_rdflib(s), _from_rdflib(p), _from_rdflib(o), graph)


# parse and serialize

def parse(input=None, format: RdfFormat | None = None, *, path=None, base_iri=None, **_):  # noqa: A002
    """The quads of an RDF document (a file at ``path``, or ``input``: text, bytes or a stream)."""
    if path is not None:
        data = Path(path).read_bytes()
    elif hasattr(input, "read"):
        data = input.read()
    else:
        data = input
    if isinstance(data, str):
        data = data.encode("utf-8")
    _keep_lexical()
    try:  # a document that cannot be read is a SyntaxError, whichever parser found it
        if format in (RdfFormat.N_QUADS, RdfFormat.TRIG):
            dataset = rdflib.Dataset()
            dataset.parse(data=data, format=format.value, publicID=base_iri)
            return iter(list(_quads_of(dataset)))
        graph = rdflib.Graph()
        graph.parse(data=data, format=format.value, publicID=base_iri)
    except Exception as error:  # noqa: BLE001 - every parser failure, as one kind
        raise SyntaxError(f"{path or 'input'}: {error}") from None
    return iter([Quad(_from_rdflib(s), _from_rdflib(p), _from_rdflib(o)) for s, p, o in graph])


def _line(statement, with_graph: bool) -> str:
    graph = getattr(statement, "graph_name", None)
    head = f"{statement.subject} {statement.predicate} {statement.object}"
    if with_graph and graph is not None and not isinstance(graph, DefaultGraph):
        head = f"{head} {graph}"
    return head + " .\n"


def serialize(input, output=None, format: RdfFormat | None = None, **_):  # noqa: A002
    """Quads or triples as RDF: N-Triples and N-Quads one statement per line, the rest by rdflib."""
    statements = list(input)
    if format in (RdfFormat.N_TRIPLES, RdfFormat.N_QUADS):
        data = "".join(_line(s, format is RdfFormat.N_QUADS) for s in statements).encode("utf-8")
    else:
        dataset = rdflib.Dataset()
        for s in statements:
            graph = getattr(s, "graph_name", None)
            target = dataset.default_graph if graph is None or isinstance(graph, DefaultGraph) else \
                dataset.graph(_to_rdflib(graph))
            target.add((_to_rdflib(s.subject), _to_rdflib(s.predicate), _to_rdflib(s.object)))
        if format in (RdfFormat.TRIG,):
            data = dataset.serialize(format=format.value, encoding="utf-8")
        else:
            union = rdflib.Graph()
            for triple in dataset.triples((None, None, None)):
                union.add(triple)
            data = union.serialize(format=format.value, encoding="utf-8")
    if output is None:
        return data
    if hasattr(output, "write"):
        output.write(data)
    else:
        Path(output).write_bytes(data)
    return None


# query results

_XSD = "http://www.w3.org/2001/XMLSchema#"
_TSV_SHORT = {  # literals written in Turtle's short form in TSV, when the lexical form allows it
    _XSD + "integer": re.compile(r"[+-]?[0-9]+"),
    _XSD + "decimal": re.compile(r"[+-]?[0-9]*\.[0-9]+"),
    _XSD + "double": re.compile(r"[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)[eE][+-]?[0-9]+"),
    _XSD + "boolean": re.compile(r"true|false"),
}


def _tsv(term) -> str:
    if term is None:
        return ""
    if isinstance(term, Literal) and not term.language:
        short = _TSV_SHORT.get(term.datatype.value)
        if short is not None and short.fullmatch(term.value):
            return term.value
    return str(term)


def _result_term(term):
    if isinstance(term, NamedNode):
        return {"type": "uri", "value": term.value}
    if isinstance(term, BlankNode):
        return {"type": "bnode", "value": term.value}
    out = {"type": "literal", "value": term.value}
    if term.language:
        out["xml:lang"] = term.language
    elif term.datatype.value != _XSD_STRING:
        out["datatype"] = term.datatype.value
    return out


_XML_HEAD = '<?xml version="1.0"?><sparql xmlns="http://www.w3.org/2005/sparql-results#">'


def _results_xml(names: list[str], rows: list[list]) -> str:
    """SPARQL Query Results XML Format."""
    out = [_XML_HEAD, "<head>", *(f'<variable name="{escape(n, quote=True)}"/>' for n in names), "</head><results>"]
    for row in rows:
        out.append("<result>")
        for name, term in zip(names, row):
            if term is None:
                continue
            if isinstance(term, NamedNode):
                value = f"<uri>{escape(term.value)}</uri>"
            elif isinstance(term, BlankNode):
                value = f"<bnode>{escape(term.value)}</bnode>"
            elif term.language:
                value = f'<literal xml:lang="{escape(term.language, quote=True)}">{escape(term.value)}</literal>'
            elif term.datatype.value != _XSD_STRING:
                value = f'<literal datatype="{escape(term.datatype.value, quote=True)}">{escape(term.value)}</literal>'
            else:
                value = f"<literal>{escape(term.value)}</literal>"
            out.append(f'<binding name="{escape(name, quote=True)}">{value}</binding>')
        out.append("</result>")
    out.append("</results></sparql>")
    return "".join(out)


class QuerySolution:
    def __init__(self, variables: list[Variable], values: list):
        self._index = {v.value: i for i, v in enumerate(variables)}
        self._values = values

    def __getitem__(self, key):
        if isinstance(key, int):
            return self._values[key]
        return self._values[self._index[key.value if isinstance(key, Variable) else str(key)]]

    def __iter__(self):
        return iter(self._values)

    def __len__(self):
        return len(self._values)


class QuerySolutions:
    def __init__(self, variables: list[Variable], rows: list[list]):
        self.variables = variables
        self._rows = rows

    def __iter__(self):
        return iter(QuerySolution(self.variables, row) for row in self._rows)

    def serialize(self, output=None, format: QueryResultsFormat = QueryResultsFormat.JSON):  # noqa: A002
        names = [v.value for v in self.variables]
        if format is QueryResultsFormat.JSON:
            bindings = [{n: _result_term(t) for n, t in zip(names, row) if t is not None} for row in self._rows]
            data = json.dumps({"head": {"vars": names}, "results": {"bindings": bindings}},
                              separators=(",", ":"), ensure_ascii=False)
        elif format is QueryResultsFormat.TSV:
            lines = ["\t".join(f"?{n}" for n in names)]
            lines += ["\t".join(_tsv(t) for t in row) for row in self._rows]
            data = "".join(line + "\n" for line in lines)
        elif format is QueryResultsFormat.CSV:
            buffer = io.StringIO()
            writer = csv.writer(buffer, lineterminator="\r\n")
            writer.writerow(names)
            for row in self._rows:
                writer.writerow(["" if t is None else (f"_:{t.value}" if isinstance(t, BlankNode) else t.value)
                                 for t in row])
            data = buffer.getvalue()
        else:
            data = _results_xml(names, self._rows)
        return _emit(data.encode("utf-8"), output)


class QueryBoolean:
    def __init__(self, value: bool):
        self._value = bool(value)

    def __bool__(self):
        return self._value

    def serialize(self, output=None, format: QueryResultsFormat = QueryResultsFormat.JSON):  # noqa: A002
        if format is QueryResultsFormat.JSON:
            data = json.dumps({"head": {}, "boolean": self._value}, separators=(",", ":"))
        elif format in (QueryResultsFormat.TSV, QueryResultsFormat.CSV):
            data = "true" if self._value else "false"
        else:
            data = _XML_HEAD + f"<head></head><boolean>{'true' if self._value else 'false'}</boolean></sparql>"
        return _emit(data.encode("utf-8"), output)


class QueryTriples:
    def __init__(self, triples: list[Triple]):
        self._triples = triples

    def __iter__(self):
        return iter(self._triples)


def _emit(data: bytes, output):
    if output is None:
        return data
    if hasattr(output, "write"):
        output.write(data)
    else:
        Path(output).write_bytes(data)
    return None


# the store

def is_store(path) -> bool:
    """Whether ``rdm graph build --store`` built a store at ``path``."""
    return (Path(path) / _STORE_FILE).is_file()


class Store:
    """An in-memory RDF dataset; given a directory, kept there as one N-Quads file."""

    def __init__(self, path: str | None = None):
        self._path = Path(path) if path is not None else None
        self._quads: dict[Quad, None] = {}
        self._dataset: dict[bool, rdflib.Dataset] = {}
        if self._path is not None and (self._path / _STORE_FILE).exists():
            self.extend(parse(path=self._path / _STORE_FILE, format=RdfFormat.N_QUADS))

    @classmethod
    def read_only(cls, path: str) -> "Store":
        if not is_store(path):
            raise FileNotFoundError(f"not a store: {path}")
        return cls(path)

    def add(self, quad: Quad) -> None:
        self._quads[quad] = None
        self._dataset.clear()

    def extend(self, quads) -> None:
        for quad in quads:
            self._quads[quad] = None
        self._dataset.clear()

    def load(self, input=None, format: RdfFormat | None = None, *, path=None, base_iri=None,  # noqa: A002
             to_graph=None, **_) -> None:
        quads = parse(input, format, path=path, base_iri=base_iri)
        self.extend(quads if to_graph is None else (Quad(q.subject, q.predicate, q.object, to_graph) for q in quads))

    def remove(self, quad: Quad) -> None:
        self._quads.pop(quad, None)
        self._dataset.clear()

    def clear(self) -> None:
        self._quads.clear()
        self._dataset.clear()

    def flush(self) -> None:
        if self._path is not None:
            self._path.mkdir(parents=True, exist_ok=True)
            (self._path / _STORE_FILE).write_bytes(serialize(self._quads, format=RdfFormat.N_QUADS))

    def __len__(self):
        return len(self._quads)

    def __iter__(self):
        return iter(list(self._quads))

    def __contains__(self, quad):
        return quad in self._quads

    def quads_for_pattern(self, subject=None, predicate=None, object=None, graph_name=None):  # noqa: A002
        return iter([q for q in self._quads if (subject is None or q.subject == subject)
                     and (predicate is None or q.predicate == predicate)
                     and (object is None or q.object == object)
                     and (graph_name is None or q.graph_name == graph_name)])

    def named_graphs(self):
        return iter({q.graph_name for q in self._quads if not isinstance(q.graph_name, DefaultGraph)})

    def _rdflib(self, union: bool) -> rdflib.Dataset:
        _keep_lexical()
        if union not in self._dataset:
            dataset = rdflib.Dataset(default_union=union)
            for q in self._quads:
                target = dataset.default_graph if isinstance(q.graph_name, DefaultGraph) else \
                    dataset.graph(_to_rdflib(q.graph_name))
                target.add((_to_rdflib(q.subject), _to_rdflib(q.predicate), _to_rdflib(q.object)))
            self._dataset[union] = dataset
        return self._dataset[union]

    def query(self, query: str, *, use_default_graph_as_union: bool = False, **_):
        try:
            result = self._rdflib(use_default_graph_as_union).query(query)
        except ParseException as error:  # what is not a query is a SyntaxError
            raise SyntaxError(str(error)) from None
        if result.type == "ASK":
            return QueryBoolean(result.askAnswer)
        if result.type in ("CONSTRUCT", "DESCRIBE"):
            return QueryTriples([Triple(_from_rdflib(s), _from_rdflib(p), _from_rdflib(o)) for s, p, o in result])
        variables = [Variable(str(v)) for v in result.vars]
        return QuerySolutions(variables, [[_from_rdflib(row[i]) for i in range(len(variables))] for row in result])
