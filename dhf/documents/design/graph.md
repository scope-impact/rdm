---
id: SDS-GRAPH-001
kind: design
context: graph
satisfies: [UN-014, UN-006, UN-003]
design_inputs: []
design_inputs:
  - id: DI-35
    text: "RDM shall project the design record into an RDF dataset with one named graph per source: user needs, bounded contexts, design inputs (text, traced user needs, owning and realising contexts) and controlled documents (id, title, revision) in a record graph; verifying-test tags in a tests graph; executed Allure results, when given, in an executions graph; and each design document's latest git commit in a git graph; with an rdfs:label on every node and RDM's vocabulary in an ontology graph; written as sorted N-Quads, byte-identical across runs over an unchanged record."
    traces_to: [UN-014]
  - id: DI-36
    text: "RDM shall load the projected dataset into a persistent Oxigraph store that each run replaces rather than merges, answer SPARQL queries over that store (or over an in-memory projection when no store is given), and serve the store as a SPARQL 1.1 HTTP endpoint whose default graph is the union of the named graphs, for graph browsers such as AWS Graph Explorer."
    traces_to: [UN-014]
  - id: DI-37
    text: "RDM shall add regulatory checklists to the graph on request, as data so that a new checklist needs no code or vocabulary change: each checklist, in rdm gap's text format (resolving includes and built-in names as rdm gap does) or as an RDF file, becomes a checklist node (a SKOS collection whose members are its own items and which links each checklist it includes); each item becomes a clause (a SKOS concept with its key as notation, its description as definition, the standard named by its key prefix as concept scheme, and its nearest listed parent clause as broader), in a checklists graph; and each controlled document's [[...]] reference tags become dcterms:references links to the clauses it references, in a references graph, using rdm gap's own key matching so that a clause no document references is exactly a clause rdm gap reports missing."
    traces_to: [UN-014, UN-006]
  - id: DI-38
    text: "RDM shall ship SHACL shapes expressing the gate rules over the graph — every user need is addressed by a design input, every design input has a passing test run and no failing or broken one, and every checklist clause is referenced by a document as violations; a design input with no tagged test file, a reference to an undeclared user need or design input, and a test tag naming no declared design input as warnings — and rdm graph validate shall run them, plus any user-supplied shape files, over the projected graph, reporting each result with its severity, focus node and message and exiting non-zero on any violation; the shapes shall block exactly the design inputs and user needs the release gate blocks."
    traces_to: [UN-014, UN-003]
  - id: DI-39
    text: "RDM shall write the projected graph as an AWS Graph Explorer graph file: every node of the record and every link between two such nodes, leaving out the vocabulary and type statements, optionally leaving out the nodes of chosen classes together with their links, and naming the served SPARQL endpoint as the file's connection, so that the whole traceability graph opens in Graph Explorer in one step."
    traces_to: [UN-014]
---

# Graph — Software Design

## Design Inputs

This context owns the projection of the design record into a linked-data
graph, refining UN-014. The Markdown design record stays the only authored
source: the graph is derived, rebuilt on demand, and never edited.

- **DI-35 (projection)** — `rdm graph build` reads what RDM already reads
  (design-doc frontmatter, the V&V plan's user-need registry, controlled
  documents' frontmatter, test-source tags, Allure results, `git log`) and
  emits quads into named graphs, one per source, so a query can always tell
  where a fact came from:
  `…graph/record`, `…graph/tests`, `…graph/executions`, `…graph/git`, and
  `…graph/ontology` (RDM's vocabulary, so a browser can label classes and
  properties). Open world: the graph states only what the record states —
  a missing `verifies` edge means *no tag was found*, never *unverified*;
  closed-world judgments stay in the gates. Every node carries an
  `rdfs:label` (its id, or a readable name) for graph browsers. Output is
  N-Quads sorted line by line — deterministic, diffable, loadable by any
  RDF tool. Refines UN-014.
- **DI-36 (store, query, serve)** — `--store DIR` loads the dataset into an
  embedded Oxigraph store, cleared first so a removed design input never
  lingers. `rdm graph query '<SPARQL>'` answers SELECT / ASK / CONSTRUCT
  over the store, or over an in-memory projection when no store is given.
  `rdm graph serve` runs `oxigraph serve` on the store with the union default
  graph and CORS enabled — the SPARQL 1.1 endpoint AWS Graph Explorer (and
  any SPARQL client) connects to. Graph Explorer browses; it never writes:
  changes are made in the Markdown and re-projected. Refines UN-014.
- **DI-37 (checklists and reference tags)** — the other half of RDM's record:
  gap analysis, modelled so checklists stay **data**. A standard is a
  `skos:ConceptScheme` named by its key prefix (`62304`, `P11`, `FDA-SW`,
  `14971`, …); a checklist item is an `rdm:Clause` (`rdfs:subClassOf
  skos:Concept`) with `skos:notation` = its key, `skos:definition` = its
  description, `skos:inScheme` = its standard, and `skos:broader` = its
  nearest listed dotted parent (`62304:5.6.2.a` → `62304:5.6.2`); a checklist
  is an `rdm:Checklist` (`rdfs:subClassOf skos:Collection`) whose
  `skos:member`s are its own items and which `rdm:includes` the checklists it
  includes — its effective contents are `rdm:includes*/skos:member`, so
  includes are never flattened away. A key shared by several checklists (the
  62304 class A/B/C lists) is one clause in several collections. Adding a
  standard is adding a file: `rdm graph build --checklist NAME|FILE`
  (repeatable) takes a built-in name, a `.txt` checklist in `rdm gap`'s format
  (read by `rdm gap`'s own reader), or an RDF file (`.ttl`, `.nt`, `.jsonld`,
  …) loaded as-is for richer metadata such as a standard's title or edition
  — the shapes (DI-38) check checklist data too. Each controlled document's
  `[[…]]` tags become `dcterms:references` links in `…graph/references`,
  matched with `rdm gap`'s own key matcher, so "a clause nothing references"
  and "a missing item" in `rdm gap` are the same set by construction.
  Refines UN-014 and UN-006.
- **DI-38 (SHACL shapes for the gates)** — `rdm/graph/shapes.ttl` states the
  gate rules as SHACL, closed-world checks over the open-world graph:
  violations (release-blocking) — a user need no design input traces to; a
  design input with no passing test run, or with a failed/broken one; a
  checklist clause no document references; warnings — a design input with no
  tagged test file, a `tracesTo` / `satisfies` / `realises` naming an
  undeclared user need or design input, a test tag naming no declared design
  input. `rdm graph validate` runs them (and any `--shapes FILE`, so a team can
  add its own rules as data) and prints severity, focus node and message per
  result, exiting 1 on a violation. The shapes sit beside the Python gates,
  not instead of them: an acceptance test holds them to agreement with
  `rdm story release-gate` and `rdm gap`. Whether shapes eventually replace
  the coded gates is RDM-004.06's question. Refines UN-014 and UN-003.
- **DI-39 (Graph Explorer file)** — Graph Explorer loads a saved graph from
  a small JSON file (`meta.kind = "graph-export"`, `data.vertices` = node
  IRIs, `data.edges` = `subject-[predicate]->object`, `data.connection` = the
  endpoint) and fetches everything else itself. `rdm graph explorer-file -o
  rdm.graph.json` writes that file for the whole record — every instance
  node and every link between two of them, without the vocabulary or
  `rdf:type` statements — so the full traceability graph opens with *Load
  graph from file* instead of a hundred manual expansions. `--exclude
  TestRun` (repeatable) leaves out a class's nodes and their links to
  declutter; `--endpoint` names the served endpoint (default
  `http://localhost:7878`). Refines UN-014.

## Design Outputs

`rdm/graph/` (optional extra `graph`: `pyoxigraph`, plus the `oxigraph` CLI
for serving):

- `rdm/graph/project.py` — `project(dhf, allure_results=None)` → quads;
  `write_nquads`; `build_store`.
- `rdm/graph/ontology.ttl` — the vocabulary. Reuses standards where they
  exist: `rdm:DesignInput rdfs:subClassOf oslc_rm:Requirement` (OSLC
  Requirements Management), `dcterms:identifier` / `dcterms:title`, PROV-O for
  commits (`prov:Activity`, `prov:wasGeneratedBy`, `prov:wasAssociatedWith`,
  `prov:endedAtTime`). RDM-specific terms only where no standard term fits:
  `rdm:UserNeed`, `rdm:BoundedContext`, `rdm:TestFile`, `rdm:TestRun`,
  `rdm:tracesTo`, `rdm:satisfies`, `rdm:ownedBy`, `rdm:realises`,
  `rdm:declaredIn`, `rdm:verifies`, `rdm:exercises`, `rdm:status`,
  `rdm:revision`, `rdm:text`.
- IRIs: vocabulary `https://github.com/scope-impact/rdm/ns#`; instances
  `urn:dhf:<project>:<kind>/<id>` (project defaults to the DHF's repository
  name, `--project` overrides), so graphs from several repositories merge
  without collisions.
- `rdm/graph/checklists.py` — checklist clauses and reference links (DI-37),
  reusing `rdm/gaps.py`'s reader and matcher.
- `rdm/graph/shapes.ttl` + `rdm/graph/validate.py` — the gate shapes and the
  pySHACL runner (DI-38).
- `rdm/graph/explorer.py` — the Graph Explorer graph file (DI-39).
- `rdm/graph/cli.py` — `rdm graph build | query | serve | validate | explorer-file`.

Acceptance criteria are verified by `@allure.story("DI-35" / "DI-36" / "DI-37" / "DI-38" / "DI-39")` tests.
