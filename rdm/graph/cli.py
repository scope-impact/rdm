"""`rdm graph build | query | serve` (DI-35, DI-36)."""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

DEFAULT_STORE = Path(".rdm/graph")

# Prefixes every query may use without declaring them (a query's own PREFIX
# for the same name wins).
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


def with_prefixes(sparql: str) -> str:
    """Prepend the standard PREFIX lines the query does not declare itself."""
    declared = {m.group(1) for m in re.finditer(r"(?im)^\s*PREFIX\s+([A-Za-z_][\w-]*)?:", sparql)}
    head = "".join(f"PREFIX {name}: <{iri}>\n" for name, iri in PREFIXES.items() if name not in declared)
    return head + sparql


def _missing_extra() -> int:
    print("Error: the graph commands need the optional extra: pip install 'rdm[graph]'")
    return 2


def graph_build_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    output: Path | None = None,
    store: Path | None = None,
    project_name: str | None = None,
    checklists: list[str] | None = None,
) -> int:
    """Project the record; write sorted N-Quads and/or (re)build a store."""
    try:
        from rdm.graph.project import build_store, nquads, project
    except ImportError:
        return _missing_extra()
    dhf = Path(dhf_dir or "dhf")
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    try:
        quads = project(dhf, allure_results_dir, project_name, checklists)
    except FileNotFoundError as error:
        print(f"Error: {error}")
        return 2
    if output is None and store is None:
        sys.stdout.write(nquads(quads))
        return 0
    if output is not None:
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        Path(output).write_text(nquads(quads), encoding="utf-8")
        print(f"wrote {output} ({len(quads)} quads)", file=sys.stderr)
    if store is not None:
        count = build_store(Path(store), quads)
        print(f"rebuilt store {store} ({count} quads)", file=sys.stderr)
    return 0


def graph_query_command(
    sparql: str,
    store: Path | None = None,
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    fmt: str = "tsv",
    checklists: list[str] | None = None,
) -> int:
    """Answer a SPARQL query over a store, or over a fresh in-memory projection."""
    try:
        import pyoxigraph as ox

        from rdm.graph.project import project
    except ImportError:
        return _missing_extra()
    if store is not None:
        if not Path(store).exists():
            print(f"Error: store not found: {store} (run `rdm graph build --store {store}` first)")
            return 2
        db = ox.Store.read_only(str(store))
    else:
        dhf = Path(dhf_dir or "dhf")
        if not dhf.exists():
            print(f"Error: DHF directory not found: {dhf}")
            return 2
        db = ox.Store()
        db.extend(project(dhf, allure_results_dir, checklists=checklists))
    try:
        # Named graphs are queried as one dataset, as `rdm graph serve` does.
        result = db.query(with_prefixes(sparql), use_default_graph_as_union=True)
    except SyntaxError as error:
        print(f"Error: invalid SPARQL: {error}")
        return 2
    if isinstance(result, ox.QueryBoolean):
        print("true" if bool(result) else "false")
    elif isinstance(result, ox.QuerySolutions):
        formats = {"tsv": ox.QueryResultsFormat.TSV, "csv": ox.QueryResultsFormat.CSV,
                   "json": ox.QueryResultsFormat.JSON}
        sys.stdout.write(result.serialize(format=formats[fmt]).decode("utf-8"))
    else:  # CONSTRUCT / DESCRIBE
        sys.stdout.write(ox.serialize(result, format=ox.RdfFormat.N_TRIPLES).decode("utf-8"))
    return 0


def serve_args(store: Path, bind: str) -> list[str]:
    """The `oxigraph serve` invocation: union default graph + CORS, so a graph
    browser (AWS Graph Explorer) sees every named graph without GRAPH clauses."""
    binary = shutil.which("oxigraph") or "oxigraph"
    return [binary, "serve", "--location", str(store), "--bind", bind, "--cors", "--union-default-graph"]


def graph_serve_command(store: Path | None = None, bind: str = "localhost:7878") -> int:
    """Serve the store as a SPARQL 1.1 endpoint (blocks until interrupted)."""
    location = Path(store or DEFAULT_STORE)
    if not location.exists():
        print(f"Error: store not found: {location} (run `rdm graph build --store {location}` first)")
        return 2
    args = serve_args(location, bind)
    print(f"SPARQL endpoint: http://{bind}/sparql  (union default graph, CORS on)", file=sys.stderr)
    try:
        return subprocess.run(args).returncode
    except FileNotFoundError:
        return _missing_extra()
    except KeyboardInterrupt:
        return 0
