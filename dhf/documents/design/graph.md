---
id: SDS-GRAPH-001
kind: design
context: graph
satisfies: [UN-014]
design_inputs: []
design_inputs:
  - id: DI-35
    text: "RDM shall project the design record into an RDF dataset with one named graph per source: user needs, bounded contexts, design inputs (text, traced user needs, owning and realising contexts) and controlled documents (id, title, revision) in a record graph; verifying-test tags in a tests graph; executed Allure results, when given, in an executions graph; and each design document's latest git commit in a git graph; with an rdfs:label on every node and RDM's vocabulary in an ontology graph; written as sorted N-Quads, byte-identical across runs over an unchanged record."
    traces_to: [UN-014]
  - id: DI-36
    text: "RDM shall load the projected dataset into a persistent Oxigraph store that each run replaces rather than merges, answer SPARQL queries over that store (or over an in-memory projection when no store is given), and serve the store as a SPARQL 1.1 HTTP endpoint whose default graph is the union of the named graphs, for graph browsers such as AWS Graph Explorer."
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
- `rdm/graph/cli.py` — `rdm graph build | query | serve`.

Acceptance criteria are verified by `@allure.story("DI-35" / "DI-36")` tests.
