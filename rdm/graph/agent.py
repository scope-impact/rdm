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

from rdm.graph import rdf as ox

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
        return project(self.dhf, self.allure_results, checklists=self.checklists, infer=True)  # DI-62

    def store(self) -> ox.Store:
        store = ox.Store()
        store.extend(self.quads())
        return store


def _value(term) -> str | None:
    return None if term is None else term.value


def schema() -> dict:
    """The vocabulary (Turtle), the prefixes every query may use undeclared, and
    the rules for the relations the graph derives rather than stores (DI-62)."""
    from rdm.graph.rules import rules

    return {"prefixes": PREFIXES, "ontology": ONTOLOGY_FILE.read_text(encoding="utf-8"),
            "rules": rules(),
            "graphs": "record, tests, executions, git, risks, checklists, references, ontology, and "
                      "inferred: what the rules derive, never stated by the record "
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


def _components(store: ox.Store, node: str, link: str) -> list[dict]:
    """The components the runs of a design input link to by ``link``, each
    with its container and owning bounded context (DI-69)."""
    return sorted(_select(store, f"""SELECT DISTINCT ?component ?name ?container ?context WHERE {{
        ?r rdm:exercises <{node}> ; {link} ?c .
        ?c dcterms:identifier ?component ; rdfs:label ?name .
        OPTIONAL {{ ?c rdm:containedIn/rdfs:label ?container }}
        OPTIONAL {{ ?c rdm:inContext/rdfs:label ?context }} }}"""), key=lambda c: c["component"])


def _input(store: ox.Store, node: str) -> dict:
    one = _select(store, f"""SELECT ?id ?text ?context ?doc ?path ?commit WHERE {{
        <{node}> dcterms:identifier ?id ; rdm:text ?text .
        OPTIONAL {{ <{node}> rdm:ownedBy/rdfs:label ?context }}
        OPTIONAL {{ <{node}> rdm:declaredIn ?d . ?d rdfs:label ?doc ; rdm:path ?path .
                   OPTIONAL {{ ?d prov:wasGeneratedBy/rdfs:label ?commit }} }} }}""")[0]
    return {
        "id": one["id"], "text": one["text"], "context": one["context"],
        "document": one["doc"] and {"id": one["doc"], "path": one["path"], "last_commit": one["commit"]},
        "needs": sorted(r["id"] for r in _select(
            store, f"SELECT ?id WHERE {{ <{node}> rdm:tracesTo/rdfs:label ?id }}")),
        "realised_by": sorted(r["c"] for r in _select(
            store, f"SELECT ?c WHERE {{ ?x rdm:realises <{node}> ; rdfs:label ?c }}")),
        "tests": sorted(r["path"] for r in _select(
            store, f"SELECT ?path WHERE {{ ?t rdm:verifies <{node}> ; rdfs:label ?path }}")),
        "code": sorted(r["p"] for r in _select(
            store, f"SELECT DISTINCT ?p WHERE {{ ?r rdm:exercises <{node}> ; rdm:exercisesOutput/rdm:path ?p }}")),
        "risks": sorted(r["id"] for r in _select(
            store, f"SELECT ?id WHERE {{ ?r rdm:controlledBy <{node}> ; dcterms:identifier ?id }}")),
        "manual_pages": sorted(r["p"] for r in _select(  # DI-77: the pages to re-read when it changes
            store, f"SELECT ?p WHERE {{ ?page rdm:namesInput <{node}> ; rdm:path ?p }}")),
        "components": _components(store, node, "rdm:namesComponent"),  # DI-69: named...
        "reached_components": _components(store, node, "rdm:reaches"),  # ...and, apart, reached (DI-73)
        "runs": sorted(({"test": r["name"], "status": r["status"], **_evidence(store, r["r"])} for r in _select(
            store, f"SELECT ?r ?name ?status WHERE {{ ?r rdm:exercises <{node}> ; rdfs:label ?name ; "
                   f"rdm:status ?status }}")), key=lambda r: (r["test"], r["status"])),
    }


def _risk(store: ox.Store, node: str) -> dict:
    values = {r["p"]: r["v"] for r in _select(
        store, f"SELECT ?p ?v WHERE {{ <{node}> ?prop ?v . BIND(REPLACE(STR(?prop), '^.*[#/]', '') AS ?p) }}")}
    controls = [r["i"] for r in _select(store, f"SELECT ?i WHERE {{ <{node}> rdm:controlledBy ?i }}")]
    declared = [i for i in controls if bool(store.query(
        with_prefixes(f"ASK {{ <{i}> a rdm:DesignInput }}"), use_default_graph_as_union=True))]
    return {
        "id": values.get("identifier"), **{name: values.get(name) for name in _RISK_FIELDS},
        "linked": sorted(r["id"] for r in _select(
            store, f"SELECT ?id WHERE {{ <{node}> rdm:linkedTo/rdfs:label ?id }}")),
        "controls": sorted((_input(store, i) for i in declared), key=lambda d: sort_key(d["id"])),
        "undeclared_controls": sorted(i.rsplit("/", 1)[-1] for i in controls if i not in declared),
    }


def trace(record: Record, ident: str) -> dict:
    """One user need (with its contexts and every input refining it), one
    design input (with its document, tests, runs and the risks it controls),
    or one risk (with its scores and each controlling input)."""
    ident = ident.strip().upper()
    store = record.store()
    if not is_id(ident):  # an id of another shape the record declares (DI-2a) is answered too
        declared = {r["id"].upper(): r["id"] for r in _select(
            store, "SELECT ?id WHERE { VALUES ?k { rdm:UserNeed rdm:DesignInput } ?n a ?k ; dcterms:identifier ?id }")}
        if ident not in declared:
            raise ValueError(f"{ident!r} is not an id (a UN-n, DI-n or risk id)")
        ident = declared[ident]
    if not ident.upper().startswith(("UN-", "DI-")):
        found = _select(store, f'SELECT ?n WHERE {{ ?n a rdm:Risk ; dcterms:identifier "{ident}" }}')
        if not found:
            raise ValueError(f"{ident} is not a declared user need (UN-n), design input (DI-n) or risk")
        return {"risk": _risk(store, found[0]["n"])}
    kind = "UserNeed" if ident.upper().startswith("UN") else "DesignInput"
    found = _select(store, f'SELECT ?n WHERE {{ ?n a rdm:{kind} ; dcterms:identifier "{ident}" }}')
    if not found:
        raise ValueError(f"{ident} is not declared in the record")
    node = found[0]["n"]
    if kind == "DesignInput":
        return {"design_input": _input(store, node)}
    text = _select(store, f"SELECT ?text WHERE {{ OPTIONAL {{ <{node}> rdm:text ?text }} }}")[0]["text"]
    inputs = [r["i"] for r in _select(store, f"SELECT ?i WHERE {{ ?i rdm:tracesTo <{node}> }} ORDER BY ?i")]
    return {"user_need": {
        "id": ident, "text": text,
        "contexts": sorted(r["c"] for r in _select(
            store, f"SELECT DISTINCT ?c WHERE {{ ?i rdm:tracesTo <{node}> . "
                   f"{{ ?i rdm:ownedBy ?x }} UNION {{ ?x rdm:realises ?i }} ?x rdfs:label ?c }}")),
        "design_inputs": sorted((_input(store, i) for i in inputs),
                                key=lambda d: sort_key(d["id"])),
    }}


def validate(record: Record) -> dict:
    """The gate shapes' results over the current record."""
    from rdm.graph.validate import validate as run_shapes

    results = run_shapes(record.quads())
    return {"violations": sum(r.severity == "Violation" for r in results),
            "warnings": sum(r.severity == "Warning" for r in results),
            "results": [{"severity": r.severity, "focus": r.label, "message": r.message} for r in results]}


INSTRUCTIONS = (
    "The design record of this project (user needs, design inputs, bounded contexts, documents, "
    "tagged tests, test runs, commits, risks, checklist clauses) as a read-only RDF graph, rebuilt from the "
    "record on every call. Start with `trace` for a UN-n, DI-n or risk id; call `schema` before writing SPARQL "
    "for `query`; `validate` runs the gate rules. Nothing here changes the record: to change it, edit "
    "the Markdown and tests and open a pull request."
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
              description="Trace a user need (UN-n), design input (DI-n) or risk id: text, contexts, owning document "
                          "and last commit, needs refined, tagged tests, runs (with steps and attachments), the source "
                          "files they exercise, the components they name and, apart, reach, "
                          "risks controlled; for a risk, its chain, scores, levels, acceptance and each "
                          "controlling design input.")
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
