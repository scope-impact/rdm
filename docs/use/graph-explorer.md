# Browsing in Graph Explorer

[AWS Graph Explorer](https://github.com/aws/graph-explorer) draws the graph
and lets you search and expand it. It only reads: the endpoint it connects to
refuses updates. Start the endpoint first:

```bash
rdm graph build --dhf dhf --allure-results dhf/allure-results \
  --checklist part11_document_control --store .rdm/graph
rdm graph serve --store .rdm/graph
```

## Start Graph Explorer

With `rdm graph serve` running, start Graph Explorer and point it at the
endpoint. On Linux, `--network host` lets the container reach `localhost:7878`:

```bash
docker run --rm --network host \
  --env HOST=localhost \
  --env PROXY_SERVER_HTTPS_CONNECTION=false \
  --env GRAPH_TYPE=sparql \
  --env USING_PROXY_SERVER=true \
  --env PUBLIC_OR_PROXY_ENDPOINT=http://localhost \
  --env GRAPH_CONNECTION_URL=http://localhost:7878 \
  public.ecr.aws/neptune/graph-explorer
```

Open <http://localhost/explorer>, search for a design input (for example
`DI-3`), add it, and expand its neighbors: its user need, owning context,
declaring document, verifying test file and test runs appear as edges.

On macOS or Windows (Docker Desktop), drop `--network host`, add
`-p 80:80`, and use `GRAPH_CONNECTION_URL=http://host.docker.internal:7878`.
Newer Graph Explorer releases need only `GRAPH_CONNECTION_URL` (the
`USING_PROXY_SERVER` / `PUBLIC_OR_PROXY_ENDPOINT` pair is the older form,
still honored). A 404 for `/rdf/statistics/summary` in the browser console is
harmless: that summary is a Neptune-only endpoint, and Graph Explorer falls
back without it.

## Make it readable

Graph Explorer keeps these settings in the browser, so you set them once:

- **Namespaces → Custom:** add `rdm` = `https://github.com/scope-impact/rdm/ns#`.
  Classes and edges then read `rdm:Clause`, `rdm:includes` instead of an
  auto-generated prefix.
- **Styles → Resources → Customize** each type: set *Display Name* to
  `rdfs:label`, so nodes show `DI-3`, `P11:11.10a`, `62304_2015_class_c`
  rather than IRIs. Every node in the graph carries a label. A different
  shape per type helps too (for example a star for standards and a tag for
  documents).
- **Search** finds nodes by `rdfs:label`; the *Class* filter set to
  `skos:ConceptScheme` with **Add All** puts every loaded standard on the
  canvas at once.
- **Expand** (sidebar) expands the selected node, optionally to one
  neighbor type, for example only `rdm:Checklist` to follow an include
  chain. Expansion is capped at 10 neighbors by default (`Settings`).

Graph Explorer v3.2.2's *Search → Query* panel runs a `SELECT` against the
endpoint and lists the rows (paste the query, **Submit**) — for example every
risk with its controls, their verifying tests and runs.
It cannot draw those rows, and it asks for `CONSTRUCT` results as JSON, which
a standard SPARQL endpoint rejects: build pictures from search and expansion
or a graph file (below), and use `rdm graph query` for scripted questions
([the record as a graph](graph.md)).

## The whole record at once

Write a Graph Explorer graph file and load it with
**Load graph from file** (the folder icon in the Graph View toolbar), with
`rdm graph serve` running:

```bash
rdm graph explorer-file --store .rdm/graph -o rdm.graph.json
# a calmer picture: leave out test runs, commits and authors
rdm graph explorer-file --store .rdm/graph -o rdm-core.graph.json \
  --exclude TestRun --exclude Activity --exclude Agent
```

The file lists every node and every link between nodes; Graph Explorer
fetches labels and properties from the endpoint (`--endpoint`, default
`http://localhost:7878`). `--exclude` also leaves out what hangs only from the
excluded nodes — without test runs, their steps, attachments and parameters
go too, so the core view holds the record and nothing that floats. Expect a cluster per bounded context — its design
inputs, their needs, documents and tests — and one per checklist.
