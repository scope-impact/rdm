# The design record as a graph

`rdm graph` projects the design record into RDF — user needs, bounded
contexts, design inputs, controlled documents, test tags, test results and git
commits — so you can query it with SPARQL and browse it visually in
[AWS Graph Explorer](https://github.com/aws/graph-explorer).

The Markdown record stays the only thing you edit. The graph is derived: it is
rebuilt from the record on every run, and Graph Explorer only browses it. To
change something, edit the Markdown and rebuild.

```bash
pip install 'rdm[graph]'     # pyoxigraph + the oxigraph CLI
```

## Build, query, serve

```bash
# Project the record (add --allure-results to include test runs)
rdm graph build --dhf dhf --allure-results dhf/allure-results --store .rdm/graph
rdm graph build --dhf dhf -o dhf.nq          # or: sorted N-Quads, diffable

# Ask questions (rdm:, rdf:, rdfs:, xsd:, dcterms:, prov:, oslc_rm: are predeclared)
rdm graph query --store .rdm/graph \
  'SELECT ?id ?text WHERE { ?i a rdm:DesignInput ; dcterms:identifier ?id ; rdm:text ?text } ORDER BY ?id'

# Serve it as a SPARQL 1.1 endpoint at http://localhost:7878/sparql
rdm graph serve --store .rdm/graph --bind 0.0.0.0:7878
```

`--store` is an embedded [Oxigraph](https://github.com/oxigraph/oxigraph)
database. Each build clears it first, so a removed design input never lingers.
Without `--store`, `rdm graph query` builds an in-memory projection on the fly.
Add `.rdm/` to `.gitignore`; the store is generated, like Allure results.

## Browse it in AWS Graph Explorer

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

## What is in the graph

One named graph per source, so a query can always tell where a fact came from.
The endpoint's default graph is the union of all of them.

| Graph | Holds |
|---|---|
| `urn:dhf:<project>:graph/record` | user needs, bounded contexts, design inputs, controlled documents |
| `…graph/tests` | verification tags found in test sources |
| `…graph/executions` | Allure results (only with `--allure-results`) |
| `…graph/git` | each design document's latest commit and its author |
| `…graph/ontology` | RDM's vocabulary, so browsers can label classes and properties |

Instances are named `urn:dhf:<project>:<kind>/<id>`, for example
`urn:dhf:rdm:input/DI-3`. `<project>` defaults to the repository name, so
graphs from several repositories can be loaded into one store without
collisions (`--project` overrides it).

The vocabulary (`rdm/graph/ontology.ttl`) reuses standards where they exist:

| Term | Meaning |
|---|---|
| `rdm:DesignInput` | a design input; a subclass of `oslc_rm:Requirement` (OSLC Requirements Management) |
| `rdm:UserNeed`, `rdm:BoundedContext`, `rdm:Document` | the other record entities |
| `rdm:tracesTo`, `rdm:ownedBy`, `rdm:satisfies`, `rdm:realises`, `rdm:declaredIn` | how they relate |
| `rdm:TestFile` `rdm:verifies` / `rdm:TestRun` `rdm:exercises`, `rdm:status` | tests and results |
| `dcterms:identifier`, `dcterms:title`, `rdm:revision` | document metadata (Dublin Core) |
| `prov:wasGeneratedBy`, `prov:Activity`, `prov:Agent`, `prov:endedAtTime` | commits and authors (PROV-O) |

## Open world

The graph states only what the record states. A missing `rdm:verifies` edge
means *no tag was found*, not *unverified*; the absence of a fact is unknown,
never false. Pass/fail judgments stay in the gates (`rdm story release-gate`).
For example, this lists design inputs with no tagged test:

```sparql
SELECT ?id WHERE {
  ?i a rdm:DesignInput ; dcterms:identifier ?id .
  FILTER NOT EXISTS { ?f rdm:verifies ?i }
}
```
