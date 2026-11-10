# The record as a graph

`rdm graph` projects the record into RDF — the spec (user needs, requirements,
Allium rules, bounded contexts), controlled documents, test results, git
commits, the risk register, and the regulatory checklists your documents are
held to — so you can query it with SPARQL, check it against the gate rules as
SHACL shapes, browse it visually in
[AWS Graph Explorer](https://github.com/aws/graph-explorer), and let agents
read it through a read-only MCP server.

The record stays the only thing anyone edits. The graph is derived: it is
rebuilt from the record on every run, and Graph Explorer only browses it. To
change something, change the spec or the Markdown and rebuild.

| Page | For |
| --- | --- |
| this page | building, querying and serving the graph, and what is in it |
| [The gates](gates.md#graph-validation) | `rdm graph validate`: the gate rules as shapes, and adding your own |
| [Browsing in Graph Explorer](graph-explorer.md) | seeing the record as a picture |
| [For agents](agents.md) | the read-only MCP server |

It needs the `graph` extra ([installation](../install/installation.md)). What the graph
holds and why is its design: [the knowledge graph](../dhf/documents/design/graph.md).

## Build, query, serve

```bash
# Project the record (add --allure-results to include test runs,
# --checklist to include the checklists your documents are held to)
rdm graph build --dhf dhf --allure-results dhf/allure-results \
  --checklist part11_document_control --store .rdm/graph
rdm graph build --dhf dhf -o dhf.nq          # or: sorted N-Quads, diffable

# Ask questions (rdm:, rdf:, rdfs:, xsd:, dcterms:, prov:, oslc_rm: are predeclared)
rdm graph query --store .rdm/graph \
  'SELECT ?id ?text WHERE { ?r a rdm:Requirement ; dcterms:identifier ?id ; rdm:text ?text } ORDER BY ?id'

# Serve it, read-only, as a SPARQL 1.1 endpoint at http://localhost:7878/sparql
rdm graph serve --store .rdm/graph            # localhost only; never bind a public address
```

`--store` is an embedded [Oxigraph](https://github.com/oxigraph/oxigraph)
database. Each build clears it first, so a removed requirement never lingers.
Without `--store`, `rdm graph query` builds an in-memory projection on the fly.
Add `.rdm/` to `.gitignore`; the store is generated, like Allure results.
A built store, and the endpoint serving it, is as current as its last build;
the agent server (`rdm graph mcp`) projects the record afresh on every call.

The endpoint is read-only. It allows requests from any origin so Graph
Explorer can reach it, which is exactly why it accepts no update and no
`SERVICE` call: any web page you have open could otherwise clear or forge the
graph you are reviewing, or have it fetch addresses on your network.
Every query is over the union of the named graphs, so `FROM` is refused
rather than silently ignored: name a graph with `GRAPH <…> { … }`.

## The whole chain in one query

Run the acceptance suite, then project everything the record knows: needs,
requirements, rules, contexts, documents, test runs, commits, and the
checklists your documents are held to.

```bash
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm graph build --allure-results dhf/allure-results \
  --checklist part11_document_control --store .rdm/graph
```

The whole chain — need → requirement → context → test runs → results — is
one query:

```sparql
SELECT ?need ?requirement ?context
       (GROUP_CONCAT(DISTINCT ?test; separator=" ") AS ?tests)
       (GROUP_CONCAT(DISTINCT ?status; separator="/") AS ?results)
WHERE {
  ?r a rdm:Requirement ; dcterms:identifier ?requirement ; rdm:ownedBy/rdfs:label ?context .
  OPTIONAL { ?r rdm:tracesTo/rdfs:label ?need }
  OPTIONAL { ?run rdm:exercises ?r ; rdfs:label ?test ; rdm:status ?status }
}
GROUP BY ?need ?requirement ?context
ORDER BY ?need ?requirement
```

## What is in the graph

One named graph per source, so a query can always tell where a fact came from.
The endpoint's default graph is the union of all of them.

| Graph | Holds |
|---|---|
| `urn:dhf:<project>:graph/record` | the spec — user needs, requirements, Allium rules, bounded contexts — and the controlled documents |
| `…graph/executions` | Allure results (only with `--allure-results`): each run's status, times, failure message and trace, parameters, steps and attachments, the requirements it claims and the source files it exercises |
| `…graph/git` | each controlled document's latest commit and its author, the commit that landed it on the default branch (`rdm:landedIn`, `rdm:landedBy`), and the commit the record was built at |
| `…graph/risks` | the risk register: each risk's chain, scores, computed levels, controls (`rdm:controlledBy`) and acceptance |
| `…graph/checklists` | the requested checklists: standards, clauses, checklists (`--checklist`) |
| `…graph/references` | documents' checklist references (`[[KEY]]`), linked to the clauses they name |
| `…graph/ontology` | RDM's vocabulary, so browsers can label classes and properties |

Instances are named `urn:dhf:<project>:<kind>/<id>`, for example
`urn:dhf:rdm:requirement/item-04-001`. `<project>` defaults to the repository name, so
graphs from several repositories can be loaded into one store without
collisions (`--project` overrides it).

The vocabulary (`rdm/graph/ontology.ttl`) reuses standards where they exist:

| Term | Meaning |
|---|---|
| `rdm:Requirement` | a stamped scenario of the spec, `item-NN-SSS`; a subclass of `oslc_rm:Requirement` (OSLC Requirements Management); `rdm:text` its title, `rdm:inRule` its Gherkin Rule, `rdm:source` its file and line, `rdm:covers` each Allium obligation it is tagged with, `rdm:coversRule` the `rdm:AlliumRule` |
| `rdm:UserNeed` | a screen of the event model with its surfaces (or a surface): `rdm:facing` its actors, `rdm:surface`, `rdm:providesRule`, `rdm:guarantee`, `rdm:command`, `rdm:stormEvent` |
| `rdm:AlliumRule`, `rdm:BoundedContext`, `rdm:Document` | the other record entities (`rdm:trigger`: the trigger a rule answers) |
| `rdm:tracesTo`, `rdm:ownedBy` | a requirement's needs, and the context of a requirement, need or rule |
| `rdm:TestRun` `rdm:exercises`, `rdm:testedAt`, `rdm:status` | results: a run, the requirements it claims, at a commit |
| `dcterms:identifier`, `dcterms:title`, `rdm:revision` | document metadata (Dublin Core) |
| `prov:wasGeneratedBy`, `prov:Activity`, `prov:Agent`, `prov:endedAtTime` | commits and authors (PROV-O) |
| `rdm:Clause` ⊂ `skos:Concept`, `rdm:Checklist` ⊂ `skos:Collection`, `skos:ConceptScheme` | clauses, checklists, standards (SKOS) |
| `dcterms:references` | a document claims a clause with a checklist reference (`[[KEY]]`), or names a document it relies on |
| `rdm:Risk`, `rdm:controlledBy`, `rdm:evaluatedAgainst` | the risk register ([risk register](risk-register.md)) |
| `rdm:landedIn`, `rdm:landedBy`, `rdm:atCommit` | the commit that landed a document's change; the commit the record was built at |

## Test results, in full

`--allure-results` brings in what each run says as evidence, not just pass or
fail — and leaves out what the record already holds or what says nothing about
design controls:

| In Allure | In the graph |
| --- | --- |
| a result file | an `rdm:TestRun` (a `prov:Activity`) with `dcterms:identifier` (uuid), `rdm:fullName`, `rdm:status`, `prov:startedAtTime` / `prov:endedAtTime` |
| `statusDetails` | `rdm:statusMessage`, `rdm:statusTrace` |
| a `tag` label `item-NN-SSS` | `rdm:exercises` the requirement |
| a `commit` label (`rdm.pytest_plugin`, item-03-002) | `rdm:testedAt` that commit; `worktree=dirty` → `rdm:uncommittedChanges` (item-06-016) |
| an `output` label (optional, a file path) | `rdm:exercisesOutput` an `rdm:SourceFile` — the code the run exercises |
| `parameters` | `rdm:parameter`, each a name and a value |
| `steps`, `attachments` | `rdm:step` (nested, ordered), `rdm:attachment` (name, media type, file) |
| other labels, `links`, `historyId`, containers | not projected: they are the project's own or runner metadata, a test case is one-to-one with its run, and fixtures (`tmp_path`, `capsys`) say nothing about a requirement. The raw files stay in the evidence bundle. |

So the chain runs all the way to code — requirement → run → source file —
from labels the tests already carry, and `trace` lists a requirement's source
files. Attachment content stays in the files (which the
release bundle keeps); the graph holds the reference.

## Evidence tied to its version

Each run links to the commit it tested, and `rdm graph validate` warns on
stale evidence, a run tied to no commit, and a run claiming an id that is no
requirement (item-06-016).

## Links between documents

For each controlled document the graph records the commit that landed its
latest change on the default branch, and who landed it; git does not know who
reviewed it (item-06-011). Two links come from frontmatter you write once (item-06-015):

```yaml
# architecture.md — the bounded contexts, each with its part
contexts:
  - {id: specification, part: Record}
  - {id: release, part: Gates}
# any controlled document — the controlled documents it relies on
references: [DC-001]
```

Naming a document the record does not hold is a violation.

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
- a `.txt` file in [`rdm gap`'s checklist format](gap-analysis.md#your-own-checklists);
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

## Open world

The graph states only what the record states. A missing `rdm:exercises` edge
means *no run claimed it*, not *unverified*; the absence of a fact is unknown,
never false. Pass/fail judgments stay in the gates (`rdm story release-gate`).
For example, this lists requirements no run claims:

```sparql
SELECT ?id WHERE {
  ?r a rdm:Requirement ; dcterms:identifier ?id .
  FILTER NOT EXISTS { ?run rdm:exercises ?r }
}
```
