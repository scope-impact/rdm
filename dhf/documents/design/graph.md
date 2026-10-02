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
  - id: DI-69
    text: "RDM's agent server shall show, in the trace of a design input, the components whose code its tests exercise, each with its container and owning bounded context."
    traces_to: [UN-017, UN-015]
---

# Knowledge graph — Software Design

## Purpose

This context owns the knowledge graph: the design record, with its test
runs, git history, risks, checklists and architecture, projected into RDF
for people and agents to query, browse and validate. It speaks of
*projection* (one source of the record turned into facts, one named graph
per source), the *vocabulary* (the classes and properties queries and agents
rely on), *gate rules* (shapes whose violations block and whose warnings
inform), and *derived relations* (facts a rule infers, kept apart from what
the record states). The graph is a read model: the Markdown record and the
tests stay the only authored source; the graph is rebuilt from them on
demand, never edited, and never the source of a fact. It is open world: it
states only what the record states, so a missing `rdm:verifies` link means
*no tag was found*, never *unverified*; closed-world judgments are the gate
rules' and the coded gates'. `graph` names it in commands and code.

## Design Outputs

The context is the optional extra `graph` (`pyoxigraph`, the `oxigraph` CLI,
`pyshacl`, the `mcp` SDK); without it every `rdm graph` command exits 2 and
names the extra. Each design input is verified by a test tagged with it in
`tests/acceptance/test_graph*.py`.

- **Projection** (`rdm/graph/`) — `project()` returns the record as
  de-duplicated quads in a stable order, `nquads` writes them sorted, and
  `build_store` clears and fills a store (DI-35, DI-36). One function per
  named graph: record (DI-35, DI-52, DI-58), tests (DI-61), executions
  (`allure.py`: DI-53, DI-54, DI-56, DI-60, DI-61), git (DI-51, DI-60),
  risks (DI-45), architecture and code (`c4.py`: DI-67), checklists and
  references on request (`checklists.py`: DI-37, DI-48), ontology, and
  inferred on request (`rules.py`: DI-62). The architecture is projected
  after the runs, because the source files it places in components
  (`rdm:inComponent`) are the ones the runs' `output` labels created; the
  code graph holds one `rdm:Dependency` per importing pair of components,
  with one example importing and imported file, beside their
  `rdm:dependsOn` link. Instance IRIs are `urn:dhf:<project>:<kind>/<id>`
  (the repository name unless `--project` is given), so graphs from several
  repositories merge without collisions; clause, standard and checklist
  IRIs (`urn:rdm:clause:62304:5.6.2`) are project-independent. `cli.py`
  holds `build | query | serve | explorer-file`; `serve` runs `oxigraph
  serve-read-only` on `.rdm/graph` at `localhost:7878` by default. `ns.py`
  prepends the prefixes a query does not declare.
- **Vocabulary** (`rdm/graph/ontology.ttl`) — reuses standard terms where
  they exist: `rdm:DesignInput rdfs:subClassOf oslc_rm:Requirement` (OSLC
  Requirements Management), Dublin Core for identifiers, titles,
  descriptions and references, PROV-O for commits, authors and run times,
  SKOS for checklists. RDM terms (`rdm:UserNeed`, `rdm:BoundedContext`,
  `rdm:Test`, `rdm:TestRun`, `rdm:Risk`, the C4 classes, `rdm:tracesTo`,
  `rdm:ownedBy`, `rdm:realises`, `rdm:verifies`, `rdm:exercises`, …) are
  minted only where none fits. It declares the derived relations with their
  rules (DI-62) and is projected as the ontology graph (DI-35).
- **Gate shapes** (`rdm/graph/shapes.ttl`) — the gate rules of DI-38, plus
  the checklist data's own shapes (one key and a standard per clause; a
  checklist's members are clauses, its includes checklists) and the shapes
  of DI-45, DI-51, DI-58, DI-60 and DI-61.
- **SHACL validation** (`rdm/graph/validate.py`) — `rdm graph validate`
  (DI-49) runs the shapes with pySHACL over the union of the named graphs
  and reports most severe first; exit 1 on a violation, 2 on a missing DHF
  or shapes file.
- **Explorer file** (`rdm/graph/explorer.py`) — the Graph Explorer file
  (DI-39); with `--exclude`, only nodes still connected to a node typed
  outside the executions graph are kept.
- **Agent server** (`rdm/graph/agent.py`) — the four tools and `rdm graph
  mcp` (DI-41, DI-42), each annotated read-only and idempotent; every call
  projects afresh with the derived relations (DI-62). `query` refuses
  `SERVICE` outside literals, IRIs and comments and anything that does not
  parse as a query; `limit` is capped at 5000. `trace` answers a user need,
  a design input (with its risks, runs, steps, attachments and source files:
  DI-45, DI-53, DI-56) or a risk. RDM's repository registers the server in
  `.mcp.json`.
  `trace` does not yet name the components a design input's tests exercise
  (DI-69): not built, and its tagged test is a failing placeholder.

Realised for other contexts (no `realises` is declared): the Projection and
Gate shapes implement the graph's part of DI-46 (specification) — each user
need's and design input's declaration count and a shape on a repeated
declaration. DI-68 (architecture) is to be met by gate shapes here; none
exists yet. Realised by others: the specification realises part of DI-61
(the test-source scan), and the pytest plugin writes the commit labels
DI-60 projects (DI-59).

## Components (C3)

![Components: graph](../../c4/views/C3_graph.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Projection | The record into RDF: named graphs, rules, the graph commands | Python, pyoxigraph | `rdm/graph/` |
| Vocabulary | Classes, properties and the rules that derive relations | Turtle | `rdm/graph/ontology.ttl` |
| Gate shapes | The gate rules as shapes | SHACL | `rdm/graph/shapes.ttl` |
| SHACL validation | `rdm graph validate` | Python, pySHACL | `rdm/graph/validate.py` |
| Explorer file | A file for Graph Explorer | Python | `rdm/graph/explorer.py` |
| Agent server | Read-only schema, query, trace and validate for agents | Python, MCP | `rdm/graph/agent.py` |

All six are in the `rdm` container; the view also shows the other contexts'
components the Projection reads (see Dependencies).

- The Projection declares its terms and rules in the Vocabulary and writes
  the explorer file with the Explorer file, which in turn imports only the
  executions graph's name from the Projection, not a second read.
- SHACL validation validates the Projection's graph and checks it with the
  Gate shapes.
- The Agent server queries the Projection, validates with SHACL validation,
  and checks `trace` input is an id with the shared kernel.
- At the container level, the Projection builds the graph store that the
  SPARQL endpoint serves to AWS Graph Explorer; the agent harness calls the
  Agent server over MCP stdio. The Agent server never uses that store: it
  projects into memory on each call.

Open questions:

- Whether the shapes replace the coded gates (RDM-004.06); until then
  acceptance tests hold the two to agreement.
- The Agent server reads `ontology.ttl` for `schema`, but the workspace
  draws no relationship to the Vocabulary.
- `schema`'s list of named graphs and the server's instructions do not name
  the architecture and code graphs (DI-67), though queries see them.

## Dependencies

A read model at the top of the dependency rule: it depends on the contexts
below it, and nothing but the composition root (`rdm/main.py`) imports it.
It projects:

- **specification**, through the record reader and the test tags
  (`rdm/specification/sdd.py`, `tags.py`): needs, inputs, contexts,
  documents, declarations, tagged tests;
- **test evidence**, through the Allure reader (`rdm/evidence/allure.py`):
  results and labels, and the verified set (`reconcile`);
- **risk**, through the Risk register (`rdm/risk/register.py`): the register,
  the policy, the residual decision and the release gate's findings;
- **architecture**, through the Architecture model (`rdm/architecture/model.py`):
  the C4 model, each file's component, the imports between components;
- **compliance**, through Gap analysis (`rdm/compliance/gaps.py`): the checklist
  reader, the built-in checklists and the key matcher.

and the shared kernel (ids, git history). It reads neither release nor
publishing. No import breaks the rule.

## Out of scope

Writing back: nothing the graph holds or derives changes the record.
Pushing the graph to an external RDF store, versioning the vocabulary and
gate shapes, and several projects in one graph are not yet in scope (see
the system architecture).
