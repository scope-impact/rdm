"""
The design record for agents, read-only (DI-41, DI-42).

Four tools — ``schema``, ``query``, ``trace``, ``validate`` — as plain
functions, and ``rdm graph mcp`` serving them over MCP stdio. Every call
projects the record afresh, so an agent never reads a stale graph. Nothing
here writes: there is no write tool, and ``query`` refuses SPARQL Update. An
agent that wants a change edits the record and opens a pull request.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pyoxigraph as ox

from rdm.graph.cli import PREFIXES, with_prefixes
from rdm.graph.ns import ReadOnlyError, read_only_query
from rdm.graph.project import ONTOLOGY_FILE, project
from rdm.kernel.ids import is_id, sort_key

ROW_LIMIT = 200
_RISK_FIELDS = ("category", "stride", "hazard", "situation", "harm", "severity", "probability", "level",
                "residualSeverity", "residualProbability", "residualLevel", "residualDecision", "acceptedBy",
                "acceptanceRationale", "riskStatus")
class Record:
    """Where the record lives; each call reads it again."""

    def __init__(self, dhf: Path, allure_results: Path | None = None, checklists: list[str] | None = None):
        self.dhf, self.allure_results, self.checklists = Path(dhf), allure_results, checklists

    def quads(self) -> list[ox.Quad]:
        return project(self.dhf, self.allure_results, checklists=self.checklists)

    def store(self) -> ox.Store:
        store = ox.Store()
        store.extend(self.quads())
        return store


def _value(term) -> str | None:
    return None if term is None else term.value


def schema() -> dict:
    """The vocabulary (Turtle) and the prefixes every query may use undeclared."""
    return {"prefixes": PREFIXES, "ontology": ONTOLOGY_FILE.read_text(encoding="utf-8"),
            "graphs": "record, tests, executions, git, risks, checklists, references and ontology "
                      "(queried as one union; GRAPH ?g { … } still works)"}


def query(record: Record, sparql: str, limit: int = ROW_LIMIT) -> dict:
    """Answer a read-only SPARQL query: SELECT rows, an ASK boolean, or
    CONSTRUCT/DESCRIBE triples — at most ``limit`` rows, flagged if cut."""
    result = read_only_query(record.store(), sparql)
    if isinstance(result, ox.QueryBoolean):
        return {"boolean": bool(result)}
    rows, truncated = [], False
    if isinstance(result, ox.QuerySolutions):
        variables = [v.value for v in result.variables]
        for solution in result:
            if len(rows) == limit:
                truncated = True
                break
            rows.append({v: _value(solution[v]) for v in variables})
        return {"variables": variables, "rows": rows, "truncated": truncated}
    for triple in result:  # CONSTRUCT / DESCRIBE
        if len(rows) == limit:
            truncated = True
            break
        rows.append([triple.subject.value, triple.predicate.value, triple.object.value])
    return {"triples": rows, "truncated": truncated}


def _select(store: ox.Store, sparql: str) -> list[dict]:
    result = store.query(with_prefixes(sparql), use_default_graph_as_union=True)
    variables = [v.value for v in result.variables]
    return [{v: _value(s[v]) for v in variables} for s in result]


def _evidence(store: ox.Store, node: str) -> dict:
    """A run's (or step's) steps, in order and nested, and its attachments (DI-53)."""
    attachments = sorted(({"name": r["name"], "type": r["type"], "file": r["file"]} for r in _select(
        store, f"SELECT ?name ?type ?file WHERE {{ <{node}> rdm:attachment ?a . ?a rdfs:label ?name ; "
               f"rdm:path ?file . OPTIONAL {{ ?a dcterms:format ?type }} }}")), key=lambda a: a["file"])
    steps = _select(store, f"SELECT ?s ?name ?status ?pos WHERE {{ <{node}> rdm:step ?s . ?s rdfs:label ?name ; "
                           f"rdm:position ?pos . OPTIONAL {{ ?s rdm:status ?status }} }}")
    steps.sort(key=lambda r: [int(n) for n in r["pos"].split(".")])
    return {"steps": [{"name": r["name"], "status": r["status"], **_evidence(store, r["s"])} for r in steps],
            "attachments": attachments}


def _requirement(store: ox.Store, node: str) -> dict:
    one = _select(store, f"""SELECT ?id ?text ?rule ?context ?source WHERE {{
        <{node}> dcterms:identifier ?id ; rdm:text ?text .
        OPTIONAL {{ <{node}> rdm:inRule ?rule }}
        OPTIONAL {{ <{node}> rdm:ownedBy/rdfs:label ?context }}
        OPTIONAL {{ <{node}> rdm:source ?source }} }}""")[0]
    return {
        "id": one["id"], "text": one["text"], "rule": one["rule"], "context": one["context"],
        "source": one["source"],
        "needs": sorted(r["id"] for r in _select(
            store, f"SELECT ?id WHERE {{ <{node}> rdm:tracesTo/rdfs:label ?id }}")),
        "covers": sorted(r["c"] for r in _select(store, f"SELECT ?c WHERE {{ <{node}> rdm:covers ?c }}")),
        "code": sorted(r["p"] for r in _select(
            store, f"SELECT DISTINCT ?p WHERE {{ ?r rdm:exercises <{node}> ; rdm:exercisesOutput/rdm:path ?p }}")),
        "risks": sorted(r["id"] for r in _select(
            store, f"SELECT ?id WHERE {{ ?r rdm:controlledBy <{node}> ; dcterms:identifier ?id }}")),
        "runs": sorted(({"test": r["name"], "status": r["status"], **_evidence(store, r["r"])} for r in _select(
            store, f"SELECT ?r ?name ?status WHERE {{ ?r rdm:exercises <{node}> ; rdfs:label ?name ; "
                   f"rdm:status ?status }}")), key=lambda r: (r["test"], r["status"])),
    }


def _risk(store: ox.Store, node: str) -> dict:
    values = {r["p"]: r["v"] for r in _select(
        store, f"SELECT ?p ?v WHERE {{ <{node}> ?prop ?v . BIND(REPLACE(STR(?prop), '^.*[#/]', '') AS ?p) }}")}
    controls = [r["i"] for r in _select(store, f"SELECT ?i WHERE {{ <{node}> rdm:controlledBy ?i }}")]
    declared = [i for i in controls if bool(store.query(
        with_prefixes(f"ASK {{ <{i}> a rdm:Requirement }}"), use_default_graph_as_union=True))]
    return {
        "id": values.get("identifier"), **{name: values.get(name) for name in _RISK_FIELDS},
        "linked": sorted(r["id"] for r in _select(
            store, f"SELECT ?id WHERE {{ <{node}> rdm:linkedTo/rdfs:label ?id }}")),
        "controls": sorted((_requirement(store, i) for i in declared), key=lambda d: sort_key(d["id"])),
        "undeclared_controls": sorted(i.rsplit("/", 1)[-1] for i in controls if i not in declared),
    }


def _values(store: ox.Store, node: str, prop: str) -> list[str]:
    return sorted(r["v"] for r in _select(store, f"SELECT ?v WHERE {{ <{node}> rdm:{prop} ?v }}"))


def trace(record: Record, ident: str) -> dict:
    """One requirement (``item-NN-SSS``: its scenario, rule, needs, runs and
    the risks it controls), one user need (a screen id such as
    ``measurement/instrument-desk``, or a surface: its actors, surfaces,
    rules, guarantees, storm events and every requirement addressing it), or
    one risk (its scores and each controlling requirement)."""
    ident = ident.strip()
    store = record.store()
    declared = {}  # every id the record declares, matched in Python: input never reaches the query text
    for row in _select(store, "SELECT ?n ?k ?id WHERE { VALUES ?k { rdm:Requirement rdm:UserNeed rdm:Risk } "
                              "?n a ?k ; dcterms:identifier ?id }"):
        declared.setdefault(row["id"], (row["n"], row["k"].rsplit("#", 1)[-1]))
    if ident not in declared:
        if not is_id(ident):
            raise ValueError(f"{ident!r} is not an id: a requirement (item-NN-SSS), user need or risk of the record")
        raise ValueError(f"{ident} is not a requirement, user need or risk of the record")
    node, kind = declared[ident]
    if kind == "Requirement":
        return {"requirement": _requirement(store, node)}
    if kind == "Risk":
        return {"risk": _risk(store, node)}
    text = _select(store, f"SELECT ?text WHERE {{ OPTIONAL {{ <{node}> rdm:text ?text }} }}")[0]["text"]
    reqs = [r["i"] for r in _select(store, f"SELECT ?i WHERE {{ ?i rdm:tracesTo <{node}> }} ORDER BY ?i")]
    return {"user_need": {
        "id": ident, "text": text,
        "context": (_select(store, f"SELECT ?c WHERE {{ OPTIONAL {{ <{node}> rdm:ownedBy/rdfs:label ?c }} }}")
                    or [{"c": None}])[0]["c"],
        "facing": _values(store, node, "facing"),
        "surfaces": _values(store, node, "surface"),
        "rules": sorted(r["r"] for r in _select(
            store, f"SELECT ?r WHERE {{ <{node}> rdm:providesRule/rdfs:label ?r }}")),
        "guarantees": _values(store, node, "guarantee"),
        "storm_events": _values(store, node, "stormEvent"),
        "requirements": sorted((_requirement(store, i) for i in reqs), key=lambda d: sort_key(d["id"])),
    }}


def validate(record: Record) -> dict:
    """The gate shapes' results over the current record."""
    from rdm.graph.validate import validate as run_shapes

    results = run_shapes(record.quads())
    return {"violations": sum(r.severity == "Violation" for r in results),
            "warnings": sum(r.severity == "Warning" for r in results),
            "results": [{"severity": r.severity, "focus": r.label, "message": r.message} for r in results]}


INSTRUCTIONS = (
    "The record of this project as a read-only RDF graph, rebuilt on every call: the spec it is held to "
    "(user needs = event-model screens and Allium surfaces; requirements = stamped Gherkin scenarios, "
    "item-NN-SSS; Allium rules), test runs, commits, risks, documents and checklist clauses. Start with "
    "`trace` for a requirement, user need or risk id; call `schema` before writing SPARQL for `query`; "
    "`validate` runs the gate rules. Nothing here changes the record: change the spec in its own "
    "repository, and the documents and tests by a pull request."
)


def server(record: Record):
    """The MCP server over ``record``: four read-only tools."""
    from mcp.server.mcpserver import MCPServer
    from mcp.server.mcpserver.exceptions import ToolError
    from mcp_types import ToolAnnotations

    app = MCPServer(name="rdm", instructions=INSTRUCTIONS)
    read_only = ToolAnnotations(readOnlyHint=True, idempotentHint=True, openWorldHint=False)

    @app.tool(name="schema", annotations=read_only,
              description="RDM's graph vocabulary (Turtle), the prefixes queries may use undeclared, "
                          "and the named graphs.")
    def _schema() -> dict:
        return schema()

    @app.tool(name="query", annotations=read_only,
              description=f"Run a read-only SPARQL query (SELECT, ASK, CONSTRUCT, DESCRIBE) over the record. "
                          f"Results are capped at `limit` rows (default {ROW_LIMIT}); `truncated` says if cut. "
                          f"SPARQL Update is refused.")
    def _query(sparql: str, limit: int = ROW_LIMIT) -> dict:
        try:
            return query(record, sparql, max(1, min(limit, 5000)))
        except ReadOnlyError as error:
            raise ToolError(str(error)) from error

    @app.tool(name="trace", annotations=read_only,
              description="Trace a requirement (item-NN-SSS: its scenario, rule, user needs, the Allium "
                          "obligations it covers, runs with steps and attachments, the files they exercise, risks "
                          "controlled), a user need (a screen id such as measurement/instrument-desk: actors, "
                          "surfaces, rules, guarantees, storm events, requirements) or a risk (its chain, scores, "
                          "levels, acceptance and each controlling requirement).")
    def _trace(id: str) -> dict:
        try:
            return trace(record, id)
        except ValueError as error:
            raise ToolError(str(error)) from error

    @app.tool(name="validate", annotations=read_only,
              description="Run the gate rules (SHACL shapes) over the record: violations block a release, "
                          "warnings do not.")
    def _validate() -> dict:
        return validate(record)

    return app


def mcp_command(dhf_dir: Path | None = None, allure_results_dir: Path | None = None,
                checklists: list[str] | None = None) -> int:
    """Run `rdm graph mcp`: serve the record over MCP stdio until the client disconnects."""
    dhf = Path(dhf_dir or "dhf")
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}", file=sys.stderr)
        return 2
    server(Record(dhf, allure_results_dir, checklists)).run("stdio")
    return 0
