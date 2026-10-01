# The design record as a graph

`rdm graph` projects the design record into RDF — user needs, bounded
contexts, design inputs, controlled documents, test tags, test results, git
commits, and the regulatory checklists your documents are held to — so you can
query it with SPARQL, check it against the gate rules as SHACL shapes, and
browse it visually in
[AWS Graph Explorer](https://github.com/aws/graph-explorer).

The Markdown record stays the only thing you edit. The graph is derived: it is
rebuilt from the record on every run, and Graph Explorer only browses it. To
change something, edit the Markdown and rebuild.

```bash
pip install 'rdm[graph]'     # pyoxigraph, the oxigraph CLI, pyshacl
```

## Build, query, serve

```bash
# Project the record (add --allure-results to include test runs,
# --checklist to include the checklists your documents are held to)
rdm graph build --dhf dhf --allure-results dhf/allure-results \
  --checklist part11_document_control --store .rdm/graph
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

### Make it readable

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

Graph Explorer v3.2.2 lists free-form query results in its *Query* panel but
cannot draw them, and it asks for `CONSTRUCT` results as JSON, which a
standard SPARQL endpoint rejects. Build views from search and expansion, and
use `rdm graph query` for questions.

## Your project's whole traceability graph

Run the acceptance suite, then project everything the record knows: needs,
design inputs, contexts, documents, test files, test runs, commits, and the
checklists your documents are held to.

```bash
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm graph build --allure-results dhf/allure-results \
  --checklist part11_document_control --store .rdm/graph
```

The whole chain — need → design input → owner → verifying tests → results —
is one query (RDM's own DHF: 13 needs, 33 inputs, 145 nodes, 269 links):

```sparql
SELECT ?need ?input ?owner
       (GROUP_CONCAT(DISTINCT ?path; separator=" ") AS ?tests)
       (GROUP_CONCAT(DISTINCT ?status; separator="/") AS ?results)
WHERE {
  ?i a rdm:DesignInput ; dcterms:identifier ?input ; rdm:ownedBy/rdfs:label ?owner .
  OPTIONAL { ?i rdm:tracesTo/rdfs:label ?need }
  OPTIONAL { ?f rdm:verifies ?i ; rdm:path ?path }
  OPTIONAL { ?r rdm:exercises ?i ; rdm:status ?status }
}
GROUP BY ?need ?input ?owner
ORDER BY ?need xsd:integer(STRAFTER(?input, "-"))
```

To see all of it at once, write a Graph Explorer graph file and load it with
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
`http://localhost:7878`). Expect a cluster per bounded context — its design
inputs, their needs, documents and tests — and one per checklist.

## What is in the graph

One named graph per source, so a query can always tell where a fact came from.
The endpoint's default graph is the union of all of them.

| Graph | Holds |
|---|---|
| `urn:dhf:<project>:graph/record` | user needs, bounded contexts, design inputs, controlled documents |
| `…graph/tests` | verification tags found in test sources |
| `…graph/executions` | Allure results (only with `--allure-results`) |
| `…graph/git` | each design document's latest commit and its author |
| `…graph/checklists` | the requested checklists: standards, clauses, checklists (`--checklist`) |
| `…graph/references` | documents' `[[KEY]]` tags, linked to the clauses they name |
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
| `rdm:Clause` ⊂ `skos:Concept`, `rdm:Checklist` ⊂ `skos:Collection`, `skos:ConceptScheme` | clauses, checklists, standards (SKOS) |
| `dcterms:references` | a document claims a clause with a `[[KEY]]` tag |

## Checklists are data

A standard, its clauses and the checklists that select them are instances,
never classes, so a new standard is a new file and nothing else:

| Concept | In the graph | Example |
|---|---|---|
| Standard | `skos:ConceptScheme`, named by the key prefix | `urn:rdm:standard:62304` |
| Clause | `rdm:Clause` with `skos:notation` (key), `skos:definition`, `skos:inScheme`, `skos:broader` (nearest listed dotted parent), `rdm:edition` | `urn:rdm:clause:62304:5.6.2` |
| Checklist | `rdm:Checklist` with `skos:member` (its own items) and `rdm:includes` (its includes) | `urn:rdm:checklist:62304_2015_class_b` |

A checklist's full contents are `rdm:includes*/skos:member`. Includes are
kept as links, not flattened. Clause, standard and checklist IRIs carry no
project name, so graphs from several repositories share one node per clause:

```sparql
# Which documents, in which projects, claim each Part 11 clause?
SELECT ?key ?doc WHERE {
  <urn:rdm:checklist:part11_document_control> rdm:includes*/skos:member ?c .
  ?c skos:notation ?key .
  OPTIONAL { ?d dcterms:references ?c . BIND (STR(?d) AS ?doc) }
} ORDER BY ?key
```

To add a checklist, pass any of these to `--checklist`:

- a built-in name (`rdm gap --list`);
- a `.txt` file in [`rdm gap`'s checklist format](checklist-format.md);
- an RDF file (`.ttl`, `.nt`, `.jsonld`, …) using the terms above, which is
  loaded as-is, so it can carry more than the text format, such as a
  standard's full title:

```turtle
@prefix rdm:  <https://github.com/scope-impact/rdm/ns#> .
@prefix skos: <http://www.w3.org/2004/02/skos/core#> .

<urn:rdm:standard:IEC81001-5-1> a skos:ConceptScheme ;
    skos:prefLabel "IEC 81001-5-1:2021 Health software security" .
<urn:rdm:clause:IEC81001-5-1:5.1.1> a rdm:Clause ;
    skos:notation "IEC81001-5-1:5.1.1" ;
    skos:definition "secure software development process" ;
    skos:inScheme <urn:rdm:standard:IEC81001-5-1> .
<urn:rdm:checklist:security> a rdm:Checklist ;
    skos:member <urn:rdm:clause:IEC81001-5-1:5.1.1> .
```

References are matched with `rdm gap`'s own key matcher: a descendant key
covers its parent (`[[62304:5.6.2.a]]` covers `62304:5.6.2`), a longer sibling
never matches a shorter key, and only `[[…]]` blocks count. So a clause no
document references in the graph is exactly an item `rdm gap` reports
missing.

## Validate against the gate rules (SHACL)

`rdm graph validate` checks the graph against the gate rules, written as SHACL
shapes in `rdm/graph/shapes.ttl`:

```bash
rdm graph validate --allure-results dhf/allure-results --checklist part11_document_control
```

| Severity | Rule |
|---|---|
| Violation | a user need no design input traces to |
| Violation | a design input with no passing test run, or with a failed / broken one |
| Violation | a checklist clause no document references |
| Violation | malformed checklist data (a clause without a key or a standard; a non-clause member) |
| Warning | a design input with no tagged test file |
| Warning | a `tracesTo` / `satisfies` / `realises` naming an undeclared need or input |
| Warning | a test tag sharing the design-input prefix but naming no declared input |

It exits 1 on any violation. The coded gates (`rdm story release-gate`,
`rdm gap`) remain authoritative; an acceptance test holds the shapes to
blocking exactly the same design inputs and user needs.

Add your own rules as more shape files, with no code change:

```bash
rdm graph validate --shapes team-rules.ttl
```

```turtle
@prefix sh:  <http://www.w3.org/ns/shacl#> .
@prefix rdm: <https://github.com/scope-impact/rdm/ns#> .
@prefix dcterms: <http://purl.org/dc/terms/> .

[] a sh:NodeShape ;
   sh:targetClass rdm:Document ;
   sh:property [ sh:path dcterms:title ; sh:minCount 1 ;
                 sh:message "every controlled document needs a title" ] .
```

## For agents (MCP, read-only)

`rdm graph mcp` serves the record to an agent as a
[Model Context Protocol](https://modelcontextprotocol.io) server over stdio,
which most agent harnesses speak. Register it once; for Claude Code, in the
project's `.mcp.json`:

```json
{
  "mcpServers": {
    "rdm": {
      "command": "rdm",
      "args": ["graph", "mcp", "--dhf", "dhf", "--allure-results", "dhf/allure-results"]
    }
  }
}
```

Other harnesses take the same command and arguments in their own MCP
settings.

| Tool | Answers |
| --- | --- |
| `trace` | a `UN-n` or `DI-n`: its text, contexts, owning document and last commit, the needs it refines, its tagged test files and their runs |
| `query` | any read-only SPARQL (SELECT, ASK, CONSTRUCT, DESCRIBE), prefixes predeclared, capped at 200 rows by default with `truncated` saying when |
| `schema` | the vocabulary and prefixes, so the agent can write its own queries |
| `validate` | the gate shapes' results: violations and warnings |

Every call projects the record afresh (a fraction of a second), so an agent
editing a branch sees its own edits on the next call — there is no store to
rebuild.

It cannot change anything. There is no write tool, and `query` refuses SPARQL
Update. An agent that wants to change the record does what a person does:
edits the Markdown and tests, and opens a pull request for review.

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
