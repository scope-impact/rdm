Feature: Graph
  RDM's own requirements in this context: each stamped scenario is one requirement,
  verified by the acceptance tests that claim it with @allure.tag("item-NN-SSS").

  @allium-guarantee.ExploreGraph
  @item-06-001
  Scenario: RDM shall project the record into an RDF dataset with one named graph per source: the spec's user needs, requirements, Allium rules and bounded contexts, and the controlled documents (id, title, revision), in a record graph; executed Allure results, when given, in an executions graph; and each controlled document's latest git commit in a git graph; with an rdfs:label on every node and RDM's vocabulary in an ontology graph; written as sorted N-Quads, byte-identical across runs over an unchanged record.

  @allium-guarantee.ExploreGraph
  @item-06-002
  Scenario: RDM shall load the projected dataset into a persistent Oxigraph store that each run replaces rather than merges, answer SPARQL queries over that store (or over an in-memory projection when no store is given), and serve the store as a read-only SPARQL 1.1 HTTP endpoint whose default graph is the union of the named graphs, for graph browsers such as AWS Graph Explorer; the endpoint and the query command shall refuse SPARQL Update and federated SERVICE calls, so neither changes the store or reaches the network; the query command shall refuse FROM, which the union default graph would ignore, and neither command shall create a store that does not exist.

  @allium-guarantee.ExploreGraph @allium-guarantee.GapAnalysis
  @item-06-003
  Scenario: RDM shall add regulatory checklists to the graph on request, as data: each checklist — in rdm gap's text format, resolving includes and built-in names as rdm gap does, or an RDF file — becomes a SKOS collection of its own items that links the checklists it includes, and each item a clause with its key, description, standard (named by the key prefix), edition when the key names one, and nearest listed parent clause.

  @allium-guarantee.ExploreGraph @allium-guarantee.ReleaseBlocked
  @item-06-004
  Scenario: RDM shall ship SHACL shapes expressing the gate rules over the graph — unaddressed user needs, requirements without a passing run or with a failing one, and unreferenced checklist clauses as violations; a requirement addressing no user need and a run claiming an id that is no requirement as warnings — that block exactly the requirements, user needs and whole-record findings the release gate blocks, and flag a checklist clause without a key or a standard as a violation.

  @allium-guarantee.ExploreGraph
  @item-06-005
  Scenario: RDM shall write the projected graph as an AWS Graph Explorer graph file: every node of the record and every link between two such nodes, leaving out the vocabulary and type statements, optionally leaving out the nodes of chosen classes together with their links and with the nodes that hang only from them (left with no path to a node outside the test-run results), refusing a class to leave out that the graph does not have, and naming the served SPARQL endpoint as the file's connection, so that the whole traceability graph opens in Graph Explorer in one step.

  @allium-guarantee.AgentReadsRecord
  @item-06-006
  Scenario: RDM shall serve the record to agents as an MCP server over stdio (rdm graph mcp) with four tools: schema (the vocabulary and the predeclared prefixes), query (SPARQL), trace (a requirement, user need or risk with its context, runs and the risks it controls) and validate (the gate shapes' results), each answering from a projection rebuilt from the record on that call.

  @allium-guarantee.AgentReadsRecord
  @item-06-007
  Scenario: RDM's agent server shall offer no way to change the record or the graph, or to reach the network: query shall accept SELECT, ASK, CONSTRUCT and DESCRIBE and reject SPARQL Update and federated SERVICE calls, trace shall accept only id-shaped input or an id the record declares, and results shall be capped at a row limit, saying when they were cut.

  @allium-guarantee.RiskRegister @allium-guarantee.AgentReadsRecord
  @item-06-008
  Scenario: RDM shall project the risk register into a risks graph — each risk with its category, STRIDE category, linked risks, hazard, situation, harm, scores, initial and residual level, residual decision (acceptable, accepted, needs acceptance, unacceptable, or not evaluated while a control lacks a passing test), status, controlling requirements and acceptance — with SHACL shapes that block exactly the risks, and the register findings, the release gate blocks and warn on a risk still proposed, and the agent server's trace shall accept a risk id and list, for a requirement, the risks it controls.

  @allium-guarantee.ExploreGraph @allium-guarantee.GapAnalysis
  @item-06-009
  Scenario: RDM shall link each controlled document to the checklist clauses its [[...]] tags reference, using rdm gap's own key matching, so that a clause no document references is exactly a clause rdm gap reports missing.

  @allium-guarantee.ExploreGraph @allium-guarantee.ReleaseBlocked
  @item-06-010
  Scenario: rdm graph validate shall run the shipped shapes and any user-supplied shape files over the projected graph, report each result with its severity, focus node and message, and exit non-zero on any violation; a shapes or checklist file that is missing or not RDF shall be reported as an error, never a traceback.

  @allium-guarantee.ExploreGraph @allium-guarantee.AgentReadsRecord
  @item-06-011
  Scenario: For each controlled document, the graph shall record the commit on the default branch's first-parent history that landed its latest change — a merge, squash or direct commit — with that commit's author, and a shape shall warn when the change has not landed on the default branch.

  @allium-guarantee.ExploreGraph @allium-guarantee.AgentReadsRecord
  @item-06-012
  Scenario: The graph shall link each risk to the document holding the risk policy it was evaluated against.

  @allium-guarantee.ExploreGraph @allium-guarantee.AgentReadsRecord @allium-guarantee.TraceVerification
  @item-06-013
  Scenario: The graph shall carry each test run's steps — name, status and order, nested under their parent step — and its attachments, on the run or on a step — name, media type and file — and the agent server's trace shall list them with each run.

  @allium-guarantee.ExploreGraph @allium-guarantee.TraceVerification
  @item-06-014
  Scenario: RDM shall project each executed Allure result's uuid, full name, start and end times, status message and trace, and parameters, and shall not project what the record already holds or what claims nothing about design controls: its labels as nodes, its links, a test case per history id, and container fixtures.

  @allium-guarantee.ExploreGraph @allium-guarantee.AgentReadsRecord
  @item-06-015
  Scenario: The graph shall link each bounded context to the controlled document whose contexts frontmatter declares it, with its part, each controlled document to the controlled documents its references frontmatter names, and shall not project the traceability matrix template, an output generated from the record; a shape shall warn on a bounded context no document declares once any document declares contexts, and validation shall fail on a reference to a document the record does not hold.

  @allium-guarantee.ExploreGraph @allium-guarantee.ReleaseBlocked
  @item-06-016
  Scenario: The graph shall link each test run to the commit its commit label names, record the commit the record was built at and whether the run tested uncommitted changes, and a shape shall warn on a run tied to no commit and on a run that tested a commit other than the record's.
