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
    text: "RDM shall add regulatory checklists to the graph on request: each checklist item (resolving includes and built-in names exactly as rdm gap does) becomes a clause node with its key and description, linked to every checklist that contains it, in a checklists graph; and each controlled document's [[...]] reference tags become links from the document to the clauses it references, in a references graph, using the same key matching as rdm gap so that a clause no document references in the graph is exactly a clause rdm gap reports missing."
    traces_to: [UN-014, UN-006]
  - id: DI-38
    text: "RDM shall ship SHACL shapes expressing the gate rules over the graph — every user need is addressed by a design input, every design input has a passing test run and no failing or broken one, and every checklist clause is referenced by a document as violations; a design input with no tagged test file, a reference to an undeclared user need or design input, and a test tag naming no declared design input as warnings — and rdm graph validate shall run them, plus any user-supplied shape files, over the projected graph, reporting each result with its severity, focus node and message and exiting non-zero on any violation; the shapes shall block exactly the design inputs and user needs the release gate blocks."
    traces_to: [UN-014, UN-003]
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
  gap analysis. `rdm graph build --checklist part11_document_control` (any
  built-in name or a checklist file, repeatable) loads each checklist into a
  `…graph/checklists` graph: a `rdm:Checklist` per checklist and a
  `rdm:Clause` per item (`dcterms:identifier` = the key, `rdm:description`),
  linked with `rdm:inChecklist` — a key shared by several checklists (the
  62304 class A/B/C lists) is one clause in several checklists. Includes and
  built-in names resolve with `rdm gap`'s own reader. Each controlled
  document's `[[…]]` reference tags become `rdm:references` links in a
  `…graph/references` graph, matched with `rdm gap`'s own key matcher
  (descendant keys cover their parent; a longer sibling never matches a
  shorter key), so "a clause nothing references" in the graph and "a missing
  item" in `rdm gap` are the same set by construction. Refines UN-014 and
  UN-006.
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
- `rdm/graph/cli.py` — `rdm graph build | query | serve | validate`.

Acceptance criteria are verified by `@allure.story("DI-35" / "DI-36" / "DI-37" / "DI-38")` tests.
