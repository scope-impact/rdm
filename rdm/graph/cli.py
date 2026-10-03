"""`rdm graph build | query | serve | explorer-file` (DI-35, DI-36, DI-39); `validate` and `mcp` live in
``validate.py`` and ``agent.py``."""

from __future__ import annotations

import sys
from pathlib import Path

from rdm.graph.ns import PREFIXES, ReadOnlyError, read_only_query, with_prefixes  # noqa: F401 (re-exported)

DEFAULT_STORE = Path(".rdm/graph")

def project_record(dhf_dir: Path | None, allure_results_dir: Path | None = None,
                   checklists: list[str] | None = None, **options) -> list | None:
    """The projection a command reads, or None after printing why (a missing
    DHF, checklist or file): the command then exits 2."""
    from rdm.graph.project import project

    dhf = Path(dhf_dir or "dhf")
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return None
    try:
        return project(dhf, allure_results_dir, checklists=checklists, **options)
    except (FileNotFoundError, SyntaxError) as error:  # SyntaxError: a checklist that is not RDF
        print(f"Error: {error}")
        return None


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
    infer: bool = False,
) -> int:
    """Project the record; write sorted N-Quads and/or (re)build a store."""
    try:
        from rdm.graph.project import build_store, nquads
    except ImportError:
        return _missing_extra()
    quads = project_record(dhf_dir, allure_results_dir, checklists, project_name=project_name, infer=infer)
    if quads is None:
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
    infer: bool = False,
) -> int:
    """Answer a SPARQL query over a store, or over a fresh in-memory projection."""
    try:
        import pyoxigraph as ox
    except ImportError:
        return _missing_extra()
    if store is not None:
        if not Path(store).exists():
            print(f"Error: store not found: {store} (run `rdm graph build --store {store}` first)")
            return 2
        if infer:
            print("Error: --infer applies to the in-memory projection, not a store: leave out --store, "
                  "or build the store with `rdm graph build --infer`")
            return 2
        db = ox.Store.read_only(str(store))
    else:
        quads = project_record(dhf_dir, allure_results_dir, checklists, infer=infer)
        if quads is None:
            return 2
        db = ox.Store()
        db.extend(quads)
    try:
        # Named graphs are queried as one dataset, as `rdm graph serve` does.
        result = read_only_query(db, sparql)
    except ReadOnlyError as error:
        print(f"Error: {error}")
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


def graph_serve_command(store: Path | None = None, bind: str = "localhost:7878") -> int:
    """Serve the store as a read-only SPARQL endpoint (blocks until interrupted)."""
    try:
        from rdm.graph.endpoint import endpoint
    except ImportError:
        return _missing_extra()
    location = Path(store or DEFAULT_STORE)
    if not location.exists():
        print(f"Error: store not found: {location} (run `rdm graph build --store {location}` first)")
        return 2
    host, _, port = bind.rpartition(":")
    try:
        server = endpoint(location, host or "localhost", int(port))
    except (ValueError, OSError) as error:
        print(f"Error: cannot serve on {bind}: {error}")
        return 2
    print(f"SPARQL endpoint: http://{bind}/sparql  (read-only, no SERVICE, union default graph, CORS on)",
          file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


def graph_explorer_file_command(
    output: Path,
    store: Path | None = None,
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    checklists: list[str] | None = None,
    endpoint: str | None = None,
    exclude: list[str] | None = None,
) -> int:
    """Write the whole record as a Graph Explorer graph file (DI-39)."""
    try:
        import pyoxigraph as ox

        from rdm.graph.explorer import DEFAULT_ENDPOINT, write_explorer_file
    except ImportError:
        return _missing_extra()
    if store is not None:
        if not Path(store).exists():
            print(f"Error: store not found: {store}")
            return 2
        quads = list(ox.Store.read_only(str(store)))
    else:
        quads = project_record(dhf_dir, allure_results_dir, checklists)
        if quads is None:
            return 2
    try:
        graph = write_explorer_file(output, quads, endpoint or DEFAULT_ENDPOINT, exclude)
    except ValueError as error:
        print(f"Error: {error}")
        return 2
    print(f"wrote {output}: {len(graph['data']['vertices'])} nodes, {len(graph['data']['edges'])} links "
          "(Graph Explorer: Load graph from file)", file=sys.stderr)
    return 0
