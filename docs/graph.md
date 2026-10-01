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
| [Gate rules as SHACL](graph-shapes.md) | `rdm graph validate`: the gate rules as shapes, and adding your own |
| [Browsing in Graph Explorer](graph-explorer.md) | seeing the record as a picture |
| [For agents](agents.md) | the read-only MCP server |

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

# Serve it, read-only, as a SPARQL 1.1 endpoint at http://localhost:7878/sparql
rdm graph serve --store .rdm/graph --bind 0.0.0.0:7878
```

`--store` is an embedded [Oxigraph](https://github.com/oxigraph/oxigraph)
database. Each build clears it first, so a removed design input never lingers.
Without `--store`, `rdm graph query` builds an in-memory projection on the fly.
Add `.rdm/` to `.gitignore`; the store is generated, like Allure results.

The endpoint is read-only (`oxigraph serve-read-only`). It allows requests
from any origin so Graph Explorer can reach it, which is exactly why it must
not accept updates: any web page you have open could otherwise clear or forge
the graph you are reviewing.

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
| `…graph/checklists` | the requested checklists: standards, clauses, checklists (`--checklist`) |
| `…graph/references` | documents' `[[KEY]]` tags, linked to the clauses they name |
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
| `rdm:Risk`, `rdm:controlledBy`, `rdm:evaluatedAgainst` | the risk register ([risk register](risk.md)) |
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
| an `output` label | `rdm:exercisesOutput` an `rdm:SourceFile` — the code the run exercises |
| `parameters` | `rdm:parameter`, each a name and a value |
| `steps`, `attachments` | `rdm:step` (nested, ordered), `rdm:attachment` (name, media type, file) |
| other labels, `links`, `historyId`, containers | not projected: `epic` / `feature` and the links are written from the record (DI-57), the rest is runner metadata, a test case is one-to-one with its run, and fixtures (`tmp_path`, `capsys`) say nothing about a design input. The raw files stay in the evidence bundle. |

So the chain runs all the way to code — design input → test → run → source
file — from labels the tests already carry, and `trace` lists a design
input's source files. Attachment content stays in the files (which the
release bundle keeps); the graph holds the reference.

## Evidence tied to its version and its test

A run is evidence for one build of one test, and the graph now says which:

- `rdm.pytest_plugin` labels each tagged run with the commit under test, and
  `worktree=dirty` when the working tree had uncommitted changes; the run
  links to the commit (`rdm:testedAt`), and the record node to the commit the
  graph was built at (`rdm:atCommit`). `rdm graph validate` warns on a run
  tied to no commit and on a run of another commit than the record's — stale
  results presented as current.
- The claim is per test, not per file: a Python test function or method
  (including those a module-level `pytestmark` tags) is an `rdm:Test`,
  `rdm:definedIn` its file, and a run links to it through Allure's full name
  (`rdm:runOf`). A file in another language, read by pattern, is one test.
  Validation warns on a tagged test that never ran while its file's other
  tests did, and on a run exercising a design input its test does not claim.

## Who landed a change, and links between documents

Git records who *landed* a change on the default branch, not who *reviewed*
it — only the forge (GitHub) knows reviewers. So for each controlled document the
graph records the commit that brought its latest change onto the default
branch: the commit itself when it was committed or squashed straight onto
it, otherwise the merge. `rdm:landedIn` points at that commit and
`rdm:landedBy` at its author. A change still on a branch has neither, and
`rdm graph validate` warns about it.

User needs link to the document that declares them (`rdm:declaredIn`), and
risks to the risk-policy document (`rdm:evaluatedAgainst`). Design documents
are not linked to the design review: the record does not say which review
covered which document, so such an edge would claim what nobody checked.

Three more links come from frontmatter you write once (DI-58):

```yaml
# architecture.md — the bounded contexts, each with its part
contexts:
  - {id: record, part: Record}
  - {id: gating, part: Gates}
# any controlled document — the controlled documents it relies on
references: [DC-001]
```

- each context `rdm:declaredIn` the architecture, with `rdm:part`; once any
  document declares contexts, `rdm graph validate` warns about a context
  whose design document it does not declare;
- `dcterms:references` from a document to each document it names; naming a
  document the record does not hold is a violation.

The traceability matrix template is not in the graph: it is an output,
rendered from what the graph already holds.

So no part of the record is an island: in RDM's own graph, the core view
(`--exclude TestRun --exclude Activity --exclude Agent`) is one connected
graph.

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

## Derived relations: rules, not facts

The graph stores only what the record states. A relation that follows from
others is not stored a second time — two copies of one fact drift — but it is
declared in the vocabulary with the rule that derives it, so a consumer can
apply the rule instead of finding nothing:

```turtle
rdm:ServesRule a rdm:Rule ;
    rdfs:comment "A bounded context serves the user needs that the design inputs it owns or realises trace to." ;
    rdm:derives rdm:serves ;
    rdm:construct """CONSTRUCT { ?context rdm:serves ?need } WHERE {
        { ?input rdm:ownedBy ?context } UNION { ?context rdm:realises ?input }
        ?input rdm:tracesTo ?need }""" .
```

```bash
rdm graph build --infer --store .rdm/graph     # add the derived facts
rdm graph query --infer 'SELECT ?c ?n WHERE { ?c rdm:serves ?n }'
```

With `--infer` the results go to their own named graph, `…graph/inferred`, so
a fact the record states and a fact a rule derived are never confused: in a
regulated record a derived link is not evidence. The agent server always
infers, and its `schema` lists every rule. Without `--infer`, nothing is
added and the rule is still there to read.

Why this way: pruning what can be derived is only safe for a consumer that
knows the rules. In Fatemi, Ravanbakhsh and Poole's experiment
([arXiv:1812.03235](https://arxiv.org/abs/1812.03235)), a model given the
rules over a graph stripped of rule-implied triples beat both a plain model
and rule inference alone — and the plain model, not given the rules, did worst.

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
