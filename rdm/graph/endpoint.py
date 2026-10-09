"""
`rdm graph serve`: the store as a read-only SPARQL 1.1 endpoint for graph
browsers such as AWS Graph Explorer (DI-36).

The endpoint is RDM's own rather than a store's own server, which would run
federated SERVICE calls: with CORS open, any web page the user has open could
have it fetch addresses on their network. Every query goes through the same guard as
the agent server's (`read_only_query`), over the store opened afresh, so the
endpoint serves the last build. Updates are refused.
"""

from __future__ import annotations

import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from rdm.graph import rdf as ox

from rdm.graph.ns import ReadOnlyError, read_only_query

_RESULTS = {  # media type -> format, for SELECT and ASK
    "application/sparql-results+json": ox.QueryResultsFormat.JSON,
    "application/json": ox.QueryResultsFormat.JSON,
    "application/sparql-results+xml": ox.QueryResultsFormat.XML,
    "text/csv": ox.QueryResultsFormat.CSV,
    "text/tab-separated-values": ox.QueryResultsFormat.TSV,
}
_GRAPHS = {  # media type -> format, for CONSTRUCT and DESCRIBE
    "application/n-triples": ox.RdfFormat.N_TRIPLES,
    "text/turtle": ox.RdfFormat.TURTLE,
    "application/rdf+xml": ox.RdfFormat.RDF_XML,
}


def _negotiate(accept: str, formats: dict, default: str) -> str:
    """The first media type of the Accept header the endpoint can write."""
    for part in accept.split(","):
        media = part.split(";")[0].strip().lower()
        if media in formats:
            return media
    return default


def answer(store: Path, sparql: str, accept: str = "") -> tuple[str, bytes]:
    """The media type and body of one query's answer over the store."""
    result = read_only_query(ox.Store.read_only(str(store)), sparql)
    if isinstance(result, (ox.QuerySolutions, ox.QueryBoolean)):
        media = _negotiate(accept, _RESULTS, "application/sparql-results+json")
        return media, result.serialize(format=_RESULTS[media])
    media = _negotiate(accept, _GRAPHS, "application/n-triples")
    return media, ox.serialize(result, format=_GRAPHS[media])


def endpoint(store: Path, host: str, port: int) -> ThreadingHTTPServer:
    """An HTTP server answering read-only SPARQL queries over ``store`` at any
    path, by GET (``?query=``) or POST (a form, or a ``application/sparql-query``
    body), with CORS open; ``serve_forever()`` runs it."""

    class Handler(BaseHTTPRequestHandler):
        def _send(self, status: int, media: str, body: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", media)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)

        def _query(self, fields: dict, body: str = "") -> None:
            if "update" in fields:
                return self._send(403, "text/plain", b"SPARQL Update is not accepted: the graph is read-only.\n")
            sparql = (fields.get("query") or [body])[0]
            if not sparql.strip():
                return self._send(400, "text/plain", b"no query given\n")
            try:
                media, payload = answer(store, sparql, self.headers.get("Accept", ""))
            except ReadOnlyError as error:
                status = 403 if "Update" in str(error) else 400
                return self._send(status, "text/plain", f"{error}\n".encode())
            self._send(200, media, payload)

        def do_GET(self) -> None:  # noqa: N802 (http.server's name)
            self._query(urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query))

        def do_POST(self) -> None:  # noqa: N802
            raw = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode("utf-8", "replace")
            kind = self.headers.get("Content-Type", "").split(";")[0].strip().lower()
            if kind == "application/sparql-query":
                return self._query({}, raw)
            if kind == "application/sparql-update":
                return self._query({"update": [raw]})
            fields = urllib.parse.parse_qs(raw)
            if urllib.parse.urlsplit(self.path).path.rstrip("/").endswith("update"):
                fields.setdefault("update", [""])
            self._query(fields)

        def do_OPTIONS(self) -> None:  # noqa: N802 (a browser's CORS preflight)
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Accept")
            self.end_headers()

        def log_message(self, *args) -> None:  # quiet: the command prints where it serves
            pass

    return ThreadingHTTPServer((host, port), Handler)
