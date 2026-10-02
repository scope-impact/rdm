---
id: SDS-GRAPH-001
kind: design
context: graph
design_inputs:
  - id: DI-35
    text: "RDM shall project the design record into an RDF dataset with one named graph per source: user needs (id, text), bounded contexts, design inputs (text, traced user needs, owning and realising contexts) and controlled documents (id, title, revision) in a record graph; verifying-test tags in a tests graph; executed Allure results, when given, in an executions graph; and each controlled document's latest git commit in a git graph; with an rdfs:label on every node and RDM's vocabulary in an ontology graph; written as sorted N-Quads, byte-identical across runs over an unchanged record."
    traces_to: [UN-014]
  - id: DI-36
    text: "RDM shall load the projected dataset into a persistent Oxigraph store that each run replaces rather than merges, answer SPARQL queries over that store (or over an in-memory projection when no store is given), and serve the store as a read-only SPARQL 1.1 HTTP endpoint, refusing updates, whose default graph is the union of the named graphs, for graph browsers such as AWS Graph Explorer."
    traces_to: [UN-014]
  - id: DI-37
    text: "RDM shall add regulatory checklists to the graph on request, as data: each checklist — in rdm gap's text format, resolving includes and built-in names as rdm gap does, or an RDF file — becomes a SKOS collection of its own items that links the checklists it includes, and each item a clause with its key, description, standard (named by the key prefix) and nearest listed parent clause."
    traces_to: [UN-014, UN-006]
  - id: DI-38
    text: "RDM shall ship SHACL shapes expressing the gate rules over the graph — unaddressed user needs, design inputs without a passing run or with a failing one, and unreferenced checklist clauses as violations; untagged design inputs, references to undeclared ids and stray test tags as warnings — that block exactly the design inputs and user needs the release gate blocks."
    traces_to: [UN-014, UN-003]
  - id: DI-39
    text: "RDM shall write the projected graph as an AWS Graph Explorer graph file: every node of the record and every link between two such nodes, leaving out the vocabulary and type statements, optionally leaving out the nodes of chosen classes together with their links and with the nodes that hang only from them (left with no path to a node outside the test-run results), and naming the served SPARQL endpoint as the file's connection, so that the whole traceability graph opens in Graph Explorer in one step."
    traces_to: [UN-014]
  - id: DI-41
    text: "RDM shall serve the design record to agents as an MCP server over stdio (rdm graph mcp) with four tools: schema (the vocabulary and the predeclared prefixes), query (SPARQL), trace (a user need or design input with its contexts, documents, tests and runs) and validate (the gate shapes' results), each answering from a projection rebuilt from the record on that call."
    traces_to: [UN-015]
  - id: DI-42
    text: "RDM's agent server shall offer no way to change the record or the graph, or to reach the network: query shall accept SELECT, ASK, CONSTRUCT and DESCRIBE and reject SPARQL Update and federated SERVICE calls, trace shall accept only id-shaped input, and results shall be capped at a row limit, saying when they were cut."
    traces_to: [UN-015]
  - id: DI-45
    text: "RDM shall project the risk register into a risks graph — each risk with its category, STRIDE category, linked risks, hazard, situation, harm, scores, initial and residual level, residual decision (acceptable, accepted, needs acceptance, unacceptable, or not evaluated while a control lacks a passing test), status, controlling design inputs and acceptance — with SHACL shapes that block exactly the risks the release gate blocks, and the agent server's trace shall accept a risk id and list, for a design input, the risks it controls."
    traces_to: [UN-016, UN-015]
  - id: DI-48
    text: "RDM shall link each controlled document to the checklist clauses its [[...]] tags reference, using rdm gap's own key matching, so that a clause no document references is exactly a clause rdm gap reports missing."
    traces_to: [UN-014, UN-006]
  - id: DI-49
    text: "rdm graph validate shall run the shipped shapes and any user-supplied shape files over the projected graph, report each result with its severity, focus node and message, and exit non-zero on any violation."
    traces_to: [UN-014, UN-003]
  - id: DI-51
    text: "For each controlled document, the graph shall record the commit on the default branch's first-parent history that landed its latest change — a merge, squash or direct commit — with that commit's author, and a shape shall warn when the change has not landed on the default branch."
    traces_to: [UN-014, UN-015]
  - id: DI-52
    text: "The graph shall link each user need to the document that declares it, and each risk to the document holding the risk policy it was evaluated against."
    traces_to: [UN-014, UN-015]
  - id: DI-53
    text: "The graph shall carry each test run's steps — name, status and order, nested under their parent step — and its attachments, on the run or on a step — name, media type and file — and the agent server's trace shall list them with each run."
    traces_to: [UN-014, UN-015, UN-004]
  - id: DI-54
    text: "RDM shall project each executed Allure result's uuid, full name, start and end times, status message and trace, and parameters, and shall not project what the record already holds or what claims nothing about design controls: its labels as nodes, its links, a test case per history id, and container fixtures."
    traces_to: [UN-014, UN-004]
  - id: DI-56
    text: "RDM shall link each test run to the source files its output labels name, and the agent server's trace shall list, for a design input, the source files its runs exercise."
    traces_to: [UN-014, UN-015]
  - id: DI-58
    text: "The graph shall link each bounded context to the controlled document whose contexts frontmatter declares it, with its part, each controlled document to the controlled documents its references frontmatter names, and shall not project the traceability matrix template, an output generated from the record; a shape shall warn on a bounded context no document declares once any document declares contexts, and validation shall fail on a reference to a document the record does not hold."
    traces_to: [UN-014, UN-015]
  - id: DI-60
    text: "The graph shall link each test run to the commit its commit label names, record the commit the record was built at and whether the run tested uncommitted changes, and a shape shall warn on a run tied to no commit and on a run that tested a commit other than the record's."
    traces_to: [UN-014, UN-003]
  - id: DI-61
    text: "The graph shall record each tagged test — a Python test function or method, including those a module-level mark tags, or the whole file where tags are read by pattern — defined in its test file and verifying the design inputs its tags name, link each test run to the test it ran through the result's full name, and, when runs are linked to tests, a shape shall warn on a tagged test with no run and on a run that exercises a design input its test does not claim."
    traces_to: [UN-014, UN-004]
  - id: DI-62
    text: "RDM shall declare in its vocabulary each relation the graph derives rather than stores, with the rule that derives it as a SPARQL CONSTRUCT — first, that a bounded context serves the user needs its owned and realised design inputs trace to; the agent server's schema shall list the rules, and when asked to infer, the projection shall add the derived facts in a separate inferred named graph and nowhere else."
    traces_to: [UN-014, UN-015]
  - id: DI-67
    text: "RDM shall project the C4 model into the graph: each element typed person, software system, container or component, with its name, technology, description and external flag; the element that contains it; the bounded context that owns each component; each relationship with its source, target, label and technology; each component's code; the component every projected source file belongs to (the longest matching code path); and, for Python, an import from one component's code into another's as a dependency between the two."
    traces_to: [UN-017, UN-014]
  - id: DI-68
    text: "RDM shall warn, never block, through the graph's gate shapes, when the C4 model and the record disagree: a design output in no component's code; a test run that exercises a component of a context that neither owns nor realises the design input it verifies; a dependency between two components with no relationship declared from the one to the other; a component in no container, or in a boundary that is not a container of the container diagram; a bounded context of the architecture with no component; a component whose code path does not exist; a relationship with no label; and an alias declared as non-external in two documents."
    traces_to: [UN-017]
  - id: DI-69
    text: "RDM's agent server shall show, in the trace of a design input, the components whose code its tests exercise, each with its container and owning bounded context."
    traces_to: [UN-017, UN-015]
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
- **DI-36 (store, query, serve)** — served read-only (`oxigraph
  serve-read-only`, Design Review 12): a read-write endpoint with open CORS let
  any web page clear or forge the graph a browser shows. `--store DIR` loads the dataset into an
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
  tagged test file, a `tracesTo` / `realises` naming an
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
  declutter, and the details that hang only from them (a run's steps,
  attachments and parameters), which would
  otherwise float as islands; `--endpoint` names the served endpoint (default
  `http://localhost:7878`). Refines UN-014.
- **DI-41 (agent server)** — `rdm graph mcp` is a Model Context Protocol
  server over stdio, the interface agent harnesses already speak. Four tools:
  `schema` (vocabulary and prefixes, so an agent can write queries),
  `query` (SPARQL), `trace` (one need or input with its contexts, documents,
  tests and runs, as JSON) and `validate` (the gate shapes' results). Each call
  projects the record afresh (about a third of a second for RDM's own), so an
  agent working on a branch never reads a stale graph. Refines UN-015.
- **DI-42 (read-only)** — amended in Design Review 12: `query` also refuses
  `SERVICE` (pyoxigraph would otherwise make the HTTP request), and `trace`
  takes only id-shaped input. The graph is the source of truth agents consult,
  never one they write. The server has no write tool; `query` rejects SPARQL
  Update and anything other than SELECT, ASK, CONSTRUCT and DESCRIBE; results
  are capped (default 200 rows) and say when they were cut, so one query
  cannot flood an agent's context. Changing the record stays a reviewed pull
  request. Refines UN-015.

- **DI-45 (risks in the graph)** — a risks graph: each `rdm:Risk` with its
  category and STRIDE category, `rdm:linkedTo` other risks, its chain, scores
  and evaluated levels, its **residual decision** — `acceptable`,
  `accepted`, `needs acceptance`, `unacceptable`, or `not evaluated` while a
  controlling design input has no passing test — its status and acceptance,
  and `rdm:controlledBy` to the design inputs that control it. The risk rules
  are written once, in `rdm/record/risk.py`: the release gate reports their
  findings, and the projection carries the same findings on each risk
  (`rdm:finding` blocks, `rdm:riskWarning` warns; a finding about the whole
  register goes on every risk), so the risk shapes block exactly what the
  gate blocks, in the gate's words. `trace` takes a `RISK-…` id, and a
  design input's trace lists the risks it controls. Refines UN-016 and
  UN-015.

- **DI-48 (reference links)** — split from DI-37: each controlled
  document's `[[…]]` tags become `dcterms:references` to the clauses they
  name, matched by `rdm gap`'s own code. Refines UN-014 and UN-006.
- **DI-49 (`rdm graph validate`)** — split from DI-38: the command that runs
  the shapes (and user shape files) and exits on a violation. Refines UN-014
  and UN-003.
- **DI-51 (who landed a change)** — git records who *landed* a change on the
  default branch, not who *reviewed* it (only the forge knows reviewers).
  For each design document the graph records the first-parent commit of the
  default branch that brought its latest change in — a merge, squash or
  direct commit — as `rdm:landedIn`, with its author as `rdm:landedBy`. A
  change not yet on the default branch has neither, and a shape warns.
  Refines UN-014 and UN-015.
- **DI-52 (no island documents)** — user needs link to the document that
  declares them (`rdm:declaredIn`), risks to the policy document they were
  evaluated against (`rdm:evaluatedAgainst`). Design documents are not linked
  to the design review: nothing in the record says which review covered which
  document, and a constant edge to the one review would claim it did. Refines
  UN-014 and UN-015.
- **DI-58 (no island documents, second pass)** — browsing RDM's own graph in
  Graph Explorer left three documents unlinked: the architecture, the
  traceability matrix and the document-control procedure. The architecture
  now declares the bounded contexts in frontmatter (`contexts:`, each with its
  part), and each context links to it (`rdm:declaredIn`, `rdm:part`); a shape
  warns on a context with a design document but no declaration, once any
  document declares contexts, so the architecture cannot silently fall behind
  the design documents. A document names the controlled documents it relies
  on in `references:` (`dcterms:references`, document to document), and a
  reference to a document the record does not hold fails validation. The
  traceability matrix template is not projected: it is an output, rendered
  from what the graph already holds. Refines UN-014
  and UN-015.

- **DI-53 (evidence in the graph)** — a passed run says nothing about what
  was checked. Each test run carries its Allure steps (`rdm:step`, in order,
  nested under their parent) and attachments (`rdm:attachment`: name, media
  type, the file in the results), and `trace` lists them, so a reviewer or
  agent goes from a design input to the evidence itself. Refines UN-014,
  UN-015 and UN-004.

- **DI-54 (Allure results, what counts)** — each run carries its uuid, full
  name, start and end times (PROV-O `startedAtTime` / `endedAtTime`), failure
  message and trace, and parameters, besides its status, steps and
  attachments. Deliberately not projected (Design Review 18): labels as
  nodes — `story` and `output` become `rdm:exercises` / `rdm:exercisesOutput`,
  epic and feature come from the record itself (DI-57), and the rest
  (`host`, `thread`, `framework`, `suite`…) is runner metadata; links, which
  repeat the record's own `declaredIn` chain; and a test case per history id,
  one-to-one with runs while one execution is loaded. The raw results stay
  in the evidence bundle (DI-30). Refines UN-014, UN-004.
- **DI-56 (runs to code)** — a test's `@allure.label("output", "rdm/…")`
  already names the code it exercises. Each run links to those files
  (`rdm:exercisesOutput` → `rdm:SourceFile`), and `trace` lists a design
  input's source files: design input → test → run → code, with nothing new
  to author. Refines UN-014, UN-015.
- **DI-60 (evidence tied to a version)** — a passing run is evidence for the
  build it ran against, and nothing said which. Each run now links to the
  commit its `commit` label names (`rdm:testedAt`, written by the plugin,
  DI-59), with `rdm:uncommittedChanges` when the working tree was not clean;
  the record node carries the commit the graph was built at
  (`rdm:atCommit`). A shape warns on a run tied to no commit (its version is
  unknown) and on a run that tested a different commit than the record's —
  stale results presented as current. Refines UN-014 and UN-003.
- **DI-61 (the claim and the evidence meet)** — the source scan said "this
  file verifies DI-n" and the results said "this run exercised DI-n", and
  nothing joined them. Each tagged test is now an `rdm:Test`: a Python test
  function or method (`tests/x.py::TestClass::test_y`, including the tests a
  module-level `pytestmark` tags), or the whole file where a language's tags
  are read by pattern, `rdm:definedIn` its `rdm:TestFile` and
  `rdm:verifies` its design inputs. A run links to the test it ran
  (`rdm:runOf`) through Allure's full name. Once runs link to tests, a shape
  warns on a tagged test with no run while other tests of its file ran (the
  claim was never executed — a file never matched, as in a language whose
  results carry no Python full name, is not reported) and on a run exercising
  a design input its test does not claim (the source and the results
  disagree). Refines UN-014 and UN-004.
- **DI-62 (derived, not stored — and the consumer is told how)** — the graph
  stores only what the record states; a fact that follows from others is not
  also stored (Design Review 17 removed `satisfies` for that reason). But a
  consumer that does not know the rule gets nothing back and, the world being
  open, no hint why. So each derived relation is declared in the vocabulary
  with the rule that derives it, as a SPARQL CONSTRUCT (`rdm:Rule`,
  `rdm:derives`, `rdm:construct`) — the first, `rdm:serves`: a bounded
  context serves the user needs its owned and realised design inputs trace
  to. The agent server's `schema` lists the rules, and its queries see their
  results. `--infer` adds the derived facts to a separate
  `…graph/inferred` named graph, so a fact the record states and a fact a
  rule derived are never confused. Refines UN-014 and UN-015.

Retired (Design Review 18): DI-55 — container fixtures (`tmp_path`,
`capsys`…) say nothing about a design input, and Allure's set-up and
tear-down halves became two fixtures each. The containers stay in the
evidence bundle (DI-30).

- **DI-67 (the C4 model in the graph)** — the elements become typed nodes
  (person, software system, container, component) with what the diagrams say
  of them, contained in their boundary; each component belongs to the
  bounded context whose design document declares it; relationships are nodes
  with source, target, label and technology. Each projected source file
  belongs to the component whose code path is its longest match (a file or a
  directory), and a Python import from one component's code into another's is
  projected as a dependency: the coupling the code actually has, beside the
  coupling the diagrams claim. Refines UN-017, UN-014.
- **DI-68 (conformance, as warnings)** — the gate shapes compare the model
  with the record and the code: code a test exercises that no component
  names; a test that exercises another context's component than the one that
  owns or realises its input; a dependency in the code with no relationship
  declared for it; a component outside any container, or inside a boundary
  the container view does not have; a context with no component; a code path
  that does not exist; an unlabelled relationship; an element declared by two
  documents. Every one is a warning: an architecture disagreement is a
  question for the reviewer, never a release block. Refines UN-017.
- **DI-69 (a design input's components)** — `trace` of a design input names
  the components its tests exercise, with their container and owning
  context, so an agent sees where an input is built without reading the code.
  Refines UN-017, UN-015.

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
  `rdm:UserNeed`, `rdm:BoundedContext`, `rdm:Test`, `rdm:TestFile`, `rdm:TestRun`,
  `rdm:tracesTo`, `rdm:ownedBy`, `rdm:realises`,
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
- risks: projected from `rdm/record/risk.py` into the `risks` named graph (DI-45).
- `rdm/graph/allure.py` — Allure results, containers and attachments → RDF (DI-53, DI-54, DI-56).
- `rdm/graph/agent.py` — the read-only agent tools and the MCP server (DI-41, DI-42; `mcp` SDK in the `graph` extra).
- `rdm/graph/cli.py` — `rdm graph build | query | serve | validate | explorer-file | mcp`.

Acceptance criteria are verified by `@allure.story("DI-35" / "DI-36" / "DI-37" / "DI-38" / "DI-39" / "DI-41" / "DI-42" / "DI-45")` tests.

## Components (C3)

The components of the `graph` context, each naming the code that
implements it; a component of another context is shown external, where this
one depends on it.

```mermaid
C4Component
  title Components: graph
  Container_Boundary(rdm_cli, "rdm") {
    Component(projection, "Projection", "Python, pyoxigraph", "The record into RDF: named graphs, rules, the graph commands", $link="rdm/graph/")
    Component(vocabulary, "Vocabulary", "Turtle", "Classes, properties and the rules that derive relations", $link="rdm/graph/ontology.ttl")
    Component(gate_shapes, "Gate shapes", "SHACL", "The gate rules as shapes", $link="rdm/graph/shapes.ttl")
    Component(shacl_validation, "SHACL validation", "Python, pySHACL", "rdm graph validate", $link="rdm/graph/validate.py")
    Component(explorer_file, "Explorer file", "Python", "A file for Graph Explorer", $link="rdm/graph/explorer.py")
    Component(agent_server, "Agent server", "Python, MCP", "Read-only schema, query, trace and validate for agents", $link="rdm/graph/agent.py")
  }
  Component_Ext(record_readers, "Record readers", "Python")
  Component_Ext(risk_register, "Risk register", "Python")
  Rel(projection, record_readers, "reads the record, results and git with")
  Rel(projection, risk_register, "reads risks and findings with")
  Rel(projection, vocabulary, "declares terms and rules in")
  Rel(projection, explorer_file, "writes the explorer file with")
  Rel(explorer_file, projection, "reads the projected graph from")
  Rel(shacl_validation, projection, "validates the graph of")
  Rel(shacl_validation, gate_shapes, "checks with")
  Rel(agent_server, projection, "queries")
  Rel(agent_server, shacl_validation, "validates with")
```
