# The design record as a graph

`rdm graph` projects the design record into RDF — user needs, bounded
contexts, design inputs, controlled documents, test tags, test results, git
commits, the risk register, and the regulatory checklists your documents are
held to — so you can query it with SPARQL, check it against the gate rules as
SHACL shapes, browse it visually in
[AWS Graph Explorer](https://github.com/aws/graph-explorer), and let agents
read it through a read-only MCP server.

The Markdown record stays the only thing you edit. The graph is derived: it is
rebuilt from the record on every run, and Graph Explorer only browses it. To
change something, edit the Markdown and rebuild.

| Page | For |
| --- | --- |
| this page | building, querying and serving the graph, and what is in it |
| [The gates](gates.md#graph-validation) | `rdm graph validate`: the gate rules as shapes, and adding your own |
| [Browsing in Graph Explorer](graph-explorer.md) | seeing the record as a picture |
| [For agents](agents.md) | the read-only MCP server |

It needs the `graph` extra ([get started](../install/getting-started.md)). What the graph
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
  'SELECT ?id ?text WHERE { ?i a rdm:DesignInput ; dcterms:identifier ?id ; rdm:text ?text } ORDER BY ?id'

# Serve it, read-only, as a SPARQL 1.1 endpoint at http://localhost:7878/sparql
rdm graph serve --store .rdm/graph --bind 0.0.0.0:7878
```

`--store` is an embedded [Oxigraph](https://github.com/oxigraph/oxigraph)
database. Each build clears it first, so a removed design input never lingers.
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
design inputs, contexts, documents, tests, test runs, commits, and the
checklists your documents are held to.

```bash
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm graph build --allure-results dhf/allure-results \
  --checklist part11_document_control --store .rdm/graph
```

The whole chain — need → design input → owner → verifying tests → results —
is one query:

```sparql
SELECT ?need ?input ?owner
       (GROUP_CONCAT(DISTINCT ?path; separator=" ") AS ?tests)
       (GROUP_CONCAT(DISTINCT ?status; separator="/") AS ?results)
WHERE {
  ?i a rdm:DesignInput ; dcterms:identifier ?input ; rdm:ownedBy/rdfs:label ?owner .
  OPTIONAL { ?i rdm:tracesTo/rdfs:label ?need }
  OPTIONAL { ?t rdm:verifies ?i ; rdm:definedIn/rdm:path ?path }
  OPTIONAL { ?r rdm:exercises ?i ; rdm:status ?status }
}
GROUP BY ?need ?input ?owner
ORDER BY ?need xsd:integer(STRAFTER(?input, "-"))
```

## What is in the graph

One named graph per source, so a query can always tell where a fact came from.
The endpoint's default graph is the union of all of them.

| Graph | Holds |
|---|---|
| `urn:dhf:<project>:graph/record` | user needs, bounded contexts, design inputs, controlled documents |
| `…graph/tests` | tagged tests found in test sources: each test, the file that defines it, and the design inputs it verifies |
| `…graph/executions` | Allure results (only with `--allure-results`): each run's status, times, failure message and trace, parameters, steps and attachments, the design inputs it exercises and the source files it exercises |
| `…graph/git` | each controlled document's latest commit and its author, the commit that landed it on the default branch (`rdm:landedIn`, `rdm:landedBy`), and the commit the record was built at |
| `…graph/risks` | the risk register: each risk's chain, scores, computed levels, controls (`rdm:controlledBy`) and acceptance |
| `…graph/architecture` | the C4 model of the [architecture workspace](documents.md#architecture-c4): each person, software system, container and component with its name, technology, description, container and external flag; each component's bounded context and code; each relationship; and the component each source file in the graph belongs to |
| `…graph/code` | Python imports from one component's code into another's, as dependencies between the components: the coupling the code actually has |
| `…graph/unit-coverage` | each component's lines its unit tests ran and lines measured (`rdm:unitLinesRun`, `rdm:unitLinesMeasured`; only with `--unit-coverage REPORT`, a Cobertura XML or LCOV report): the component's own unit-test evidence, linked to no run and no design input |
| `…graph/checklists` | the requested checklists: standards, clauses, checklists (`--checklist`) |
| `…graph/references` | documents' `[[KEY]]` tags, linked to the clauses they name |
| `…graph/manual` | the user manual: each page a `kind: manual` document lists, the design inputs it names, and the labels of each test example it shows |
| `…graph/ontology` | RDM's vocabulary, so browsers can label classes and properties, and the rules for derived relations |
| `…graph/inferred` | what the rules derive (only with `--infer`, and always for the agent server); never stated by the record |

Instances are named `urn:dhf:<project>:<kind>/<id>`, for example
`urn:dhf:rdm:input/DI-3`. `<project>` defaults to the repository name, so
graphs from several repositories can be loaded into one store without
collisions (`--project` overrides it).

The vocabulary (`rdm/graph/ontology.ttl`) reuses standards where they exist:

| Term | Meaning |
|---|---|
| `rdm:DesignInput` | a design input; a subclass of `oslc_rm:Requirement` (OSLC Requirements Management) |
| `rdm:UserNeed`, `rdm:BoundedContext`, `rdm:Document` | the other record entities |
| `rdm:tracesTo`, `rdm:ownedBy`, `rdm:realises`, `rdm:declaredIn` | how they relate (a context's needs: `?i rdm:ownedBy ?c ; rdm:tracesTo ?need`) |
| `rdm:Test` `rdm:verifies`, `rdm:definedIn` `rdm:TestFile` / `rdm:TestRun` `rdm:exercises`, `rdm:runOf`, `rdm:testedAt`, `rdm:status` | tests and results: a test is a function (or a whole file read by pattern); a run is a run of a test, at a commit |
| `dcterms:identifier`, `dcterms:title`, `rdm:revision` | document metadata (Dublin Core) |
| `prov:wasGeneratedBy`, `prov:Activity`, `prov:Agent`, `prov:endedAtTime` | commits and authors (PROV-O) |
| `rdm:Clause` ⊂ `skos:Concept`, `rdm:Checklist` ⊂ `skos:Collection`, `skos:ConceptScheme` | clauses, checklists, standards (SKOS) |
| `dcterms:references` | a document claims a clause with a `[[KEY]]` tag, or names a document it relies on |
| `rdm:Risk`, `rdm:controlledBy`, `rdm:evaluatedAgainst` | the risk register ([risk register](risk-register.md)) |
| `rdm:Person`, `rdm:SoftwareSystem`, `rdm:Container`, `rdm:Component` ⊂ `rdm:ArchitectureElement`; `rdm:containedIn`, `rdm:inContext`, `rdm:code`, `rdm:Relationship` | the C4 model (`?c a rdm:Component ; rdm:inContext ?ctx`) |
| `rdm:inComponent`, `rdm:dependsOn`, `rdm:Dependency`, `rdm:dependenciesNotRead` | a source file's component; one component's code importing another's (read from Python only: a component whose code holds none is marked not read) |
| `rdm:namesComponent`, `rdm:unknownComponent`; `rdm:reaches` (derived) | the components a run names; a `component` label naming none; the components its named ones lead to by declared relationships |
| `rdm:landedIn`, `rdm:landedBy`, `rdm:atCommit` | the commit that landed a document's change; the commit the record was built at |

## Test results, in full

`--allure-results` brings in what each run says as evidence, not just pass or
fail — and leaves out what the record already holds or what says nothing about
design controls:

| In Allure | In the graph |
| --- | --- |
| a result file | an `rdm:TestRun` (a `prov:Activity`) with `dcterms:identifier` (uuid), `rdm:fullName`, `rdm:status`, `prov:startedAtTime` / `prov:endedAtTime` |
| `statusDetails` | `rdm:statusMessage`, `rdm:statusTrace` |
| a `story` label | `rdm:exercises` the design input |
| `fullName` | `rdm:runOf` the `rdm:Test` (`tests/x.py::TestClass::test_y`) it ran (DI-61) |
| a `commit` label (`rdm.pytest_plugin`, DI-59) | `rdm:testedAt` that commit; `worktree=dirty` → `rdm:uncommittedChanges` (DI-60) |
| a `component` label (the way a test names what it exercises) | `rdm:namesComponent` the C4 component with that key; a key the model does not declare is `rdm:unknownComponent`, a warning (DI-56) |
| an `output` label (optional, a file path) | `rdm:exercisesOutput` an `rdm:SourceFile` — the code the run exercises — and `rdm:namesComponent` the component holding it |
| `parameters` | `rdm:parameter`, each a name and a value |
| `steps`, `attachments` | `rdm:step` (nested, ordered), `rdm:attachment` (name, media type, file) |
| other labels, `links`, `historyId`, containers | not projected: `epic` / `feature` and the links are written from the record (DI-57), the rest is runner metadata, a test case is one-to-one with its run, and fixtures (`tmp_path`, `capsys`) say nothing about a design input. The raw files stay in the evidence bundle. |

So the chain runs all the way to code — design input → test → run → source
file — from labels the tests already carry, and `trace` lists a design
input's source files. Attachment content stays in the files (which the
release bundle keeps); the graph holds the reference.

## Evidence tied to its version and its test

Each run links to the commit it tested and to the test function it ran, and
`rdm graph validate` warns on stale or unclaimed evidence (DI-60, DI-61).

## Links between documents

For each controlled document the graph records the commit that landed its
latest change on the default branch, and who landed it; git does not know who
reviewed it (DI-51). Two links come from frontmatter you write once (DI-58):

```yaml
# architecture.md — the bounded contexts, each with its part
contexts:
  - {id: specification, part: Record}
  - {id: release, part: Gates}
# any controlled document — the controlled documents it relies on
references: [DC-001]
```

Naming a document the record does not hold is a violation; a context whose
design document the architecture does not declare is a warning.

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

## Derived relations: rules, not facts

A relation that follows from others, such as the user needs a bounded context
serves, is not stored a second time; the vocabulary declares it with the
SPARQL rule that derives it (DI-62). `--infer` adds what the rules derive, in
its own named graph, so a stated fact and a derived one are never confused:

```bash
rdm graph build --infer --store .rdm/graph
rdm graph query --infer 'SELECT ?c ?n WHERE { ?c rdm:serves ?n }'
```

A second rule derives `rdm:reaches` (DI-73): a run reaches each component the
declared relationships lead to, at any depth, from a component it names, and
that it does not name itself. Reaching over-approximates, so only a named
component counts for the C4 warnings (DI-68).

Why rules rather than stored copies: in Fatemi, Ravanbakhsh and Poole's
experiment ([arXiv:1812.03235](https://arxiv.org/abs/1812.03235)), a model
given the rules over a graph stripped of rule-implied triples beat both a
plain model and rule inference alone.

## Open world

The graph states only what the record states. A missing `rdm:verifies` edge
means *no tag was found*, not *unverified*; the absence of a fact is unknown,
never false. Pass/fail judgments stay in the gates (`rdm story release-gate`).
For example, this lists design inputs with no tagged test:

```sparql
SELECT ?id WHERE {
  ?i a rdm:DesignInput ; dcterms:identifier ?id .
  FILTER NOT EXISTS { ?t rdm:verifies ?i }
}
```
