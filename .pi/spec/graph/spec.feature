Feature: Graph
  The accepted scenarios of this context: the record projected into an RDF
  graph that people query and explore and agents read, never edit. Each is
  stamped with an item id that is never renumbered or reused, and verified by
  the acceptance tests whose Allure results carry that id as a tag label.

  Rule: The record is projected into one named graph per source, deterministically

    # graph.allium, surface ExploreGraph, guarantee RecordProjectedPerSource
    @allium-guarantee.RecordProjectedPerSource
    @item-06-001
    Scenario: A committed record becomes a dataset of named graphs, byte-identical while the record is unchanged
      Given a committed repository whose spec holds the needs Alarm and Logging as Features and the requirements item-01-001 and item-01-002 under them
      And controlled documents with id, title and revision, and Allure results
      When the author builds the graph
      Then the record graph holds each user need with its Feature's title as id and its description as text
      And each requirement with its text, the needs it addresses, its context and its source line, and each bounded context
      And read through an adapter (Löyly's layout, Allium), the graph holds what a need faces, provides and guarantees, the rules a requirement covers, the Allium rules, and a context with no requirement
      And each controlled document with its id, title and revision
      And an executions graph only when results are given
      And a git graph with the design document's latest commit, its time and its author
      And an rdfs:label on every typed node
      And an ontology graph of RDM's vocabulary, reusing OSLC RM
      And the dataset is written as sorted N-Quads, byte-identical for an unchanged record
      And a literal holding a Unicode line separator stays one statement

  Rule: The projection is stored, queried and served read-only

    # graph.allium, surface ExploreGraph, guarantee StoreQueriedAndServedReadOnly
    @allium-guarantee.StoreQueriedAndServedReadOnly
    @item-06-002
    Scenario: The store is replaced on each build, queried with SPARQL and served read-only
      Given a graph built into a store from a record with item-01-001 and item-01-002
      When the author queries the store with the standard prefixes undeclared and no GRAPH clause
      Then the query answers over the union of the named graphs
      When item-01-002 is removed and the graph is built again
      Then the store holds no item-01-002: it was replaced, not merged
      And with no store given, the query answers from an in-memory projection, ASK and CONSTRUCT included
      And a SPARQL Update or a SERVICE call is refused: nothing changes, nothing reaches the network
      And FROM, which the union default graph would ignore, is refused, and so is SERVICE with a variable endpoint, while a prefix named service is no call
      When the store is served over HTTP
      Then the endpoint's default graph is the union, the standard prefixes are predeclared and CORS is open
      And an update from another origin is refused and the data stays
      And SERVICE is refused, even after a comment ended by a carriage return
      And the endpoint answers from the last build
      And a store that does not exist is refused, not created

  Rule: Checklists join the graph as data

    # graph.allium, surface ExploreGraph, guarantee ChecklistsAsSkos
    @allium-guarantee.ChecklistsAsSkos
    @item-06-003
    Scenario: A text checklist, a built-in one and an RDF one become SKOS collections of clauses
      Given a text checklist that includes a built-in checklist by name, and a checklist written in RDF
      When the author builds the graph with those checklists
      Then each checklist is a SKOS collection of its own clauses that links the checklists it includes
      And the built-in checklist is resolved by its name
      And each clause has its key, description, standard, edition and nearest listed parent clause
      And the RDF checklist is loaded as it is, its nodes labelled like any other

    # graph.allium, surface ExploreGraph, guarantee DocumentsLinkTheirClauses
    @allium-guarantee.DocumentsLinkTheirClauses
    @item-06-009
    Scenario: A document's reference tags link it to the clauses rdm gap would match
      Given documents whose [[...]] tags reference some clauses of a checklist and not others
      When the author builds the graph with that checklist
      Then each document links to the clauses its tags reference, matched as rdm gap matches keys
      And the clauses no document references are exactly those rdm gap reports missing

  Rule: The gate rules hold over the graph as SHACL shapes

    # graph.allium, surface ExploreGraph, guarantee GateRulesAsShapes
    @allium-guarantee.GateRulesAsShapes
    @item-06-004
    Scenario: The shapes block exactly what the release gate blocks, each finding on its node
      Given records with the requirements item-01-001 and item-01-002, each with these needs and runs:
        | case             | needs               | runs                                              | blocked     |
        | all pass         | Need1, Need2        | item-01-001 passed; item-01-002 passed            | nothing     |
        | failed           | Need1, Need2        | item-01-001 passed and failed; item-01-002 passed | item-01-001 |
        | broken           | Need1, Need2        | item-01-001 passed and broken; item-01-002 passed | item-01-001 |
        | untested         | Need1, Need2        | item-01-001 passed                                | item-01-002 |
        | skipped only     | Need1, Need2        | item-01-001 passed; item-01-002 skipped           | item-01-002 |
        | unaddressed need | Need1, Need2, Need3 | item-01-001 passed; item-01-002 passed            | Need3       |
      When the shapes are validated over each record's graph and the release gate is run on it
      Then for each case both block exactly what the table names
      When the needs are Need1, Need2 and Need3 and item-01-001's only run failed
      Then Need3 is a violation "user need is addressed by no requirement"
      And item-01-001 is a violation "requirement has a failed or broken test run"
      And item-01-002 is a violation "requirement is not verified by any passing test run"
      And a checklist clause no document references, and a clause without a key or a standard, are violations
      And a requirement addressing no user need, and a run claiming an id that is no requirement, are warnings
      And what the release gate blocks about the whole record, the shapes block too
      And the executions graph reads results as the gates do: a byte-order mark is read, a failed step fails its run

    # graph.allium, rule GraphValidate (requires 1 to 2)
    @allium-rule-success.GraphValidate @allium-rule-failure.GraphValidate.1 @allium-rule-failure.GraphValidate.2
    @item-06-010
    Scenario: rdm graph validate reports each result and exits on a violation
      Given a record whose graph has violations, and one without
      When the author runs rdm graph validate on each
      Then each result is a line with its severity, focus node and message
      And the command exits 1 on the record with violations and 0 on the other
      And a user-supplied shapes file adds its rules as data
      When a shapes or checklist file is not RDF
      Then the command reports an error and exits 2, never with a traceback

  Rule: The graph can be opened in AWS Graph Explorer as a file

    # graph.allium, rule ExplorerFile (requires 1)
    @allium-rule-success.ExplorerFile @allium-rule-failure.ExplorerFile.1
    @item-06-005
    Scenario: The whole record is written as a Graph Explorer file, a class left out on request
      Given a record with needs, requirements, documents, risks and test runs
      When the author writes the graph as a Graph Explorer file
      Then the file has Graph Explorer's envelope and connection
      And every node of the record, of every kind, and nothing from the vocabulary
      And every link between two record nodes, in Graph Explorer's edge-id form, and no rdf:type statement
      When a class is left out
      Then its nodes and their links are dropped, with the nodes that hang only from them, and only those
      And the command writes the file from a fresh projection or from a built store
      When a class to leave out is one the graph does not have
      Then it is refused, naming the classes the graph has

  Rule: An agent reads the record through four read-only tools

    # graph.allium, rule ServeAgent
    @allium-rule-success.ServeAgent
    @item-06-006
    Scenario: The agent server answers schema, query, trace and validate from the current record
      Given a record with the needs Alarm and Logging, the requirements item-01-001 and item-01-002, and a passing run of item-01-001
      When an agent connects to rdm graph mcp
      Then it is offered exactly four tools: schema, query, trace and validate
      And schema gives the vocabulary and the predeclared prefixes
      And query answers SPARQL with those prefixes predeclared
      And trace of item-01-001 gives its scenario, context, source, needs, coverage and runs
      And trace of a need gives its text, context and requirements, and what an adapter adds (faces, provides, guarantees) is empty
      And validate gives the gate shapes' results: item-01-002 has no passing run
      When the record is edited during the session
      Then the next call answers from the edited record

    # graph.allium, surface AgentReadsRecord, guarantee AgentCannotChangeTheRecord
    @allium-guarantee.AgentCannotChangeTheRecord
    @item-06-007
    Scenario: The agent server cannot change the record or reach the network
      Given the agent server over a record
      Then its four tools are all declared read-only and non-destructive
      When the agent sends every form of SPARQL Update
      Then each is refused and nothing in the record changed
      When the agent sends SERVICE in any case, with an IRI or a variable endpoint
      Then it is refused, while the word inside a literal is just text
      When the agent traces input that is not id-shaped
      Then it is refused as "not an id"
      And trace answers an id the record declares whatever its shape, such as the need Logging, and nothing built from it
      And SELECT, ASK, CONSTRUCT and DESCRIBE all work
      And rows beyond the cap are cut and the cut is reported; under the cap nothing is flagged

  Rule: Risks are in the graph, with shapes that block what the release gate blocks

    # ../risk/allium/risk.allium, surface RiskRegisterDesk, guarantee RisksProjectedWithShapes
    @allium-guarantee.RisksProjectedWithShapes
    @item-06-008
    Scenario: Each risk's facts are in the risks graph, and the shapes block the risks the gate blocks
      Given a risk register with safety and security risks, their scores, controls and acceptances, and a risk policy
      When the graph is built
      Then the risks graph holds each risk's facts: category, STRIDE category, linked risks, hazard, situation, harm, scores, levels, residual decision, status, controls and acceptance
      And the residual decision is "not evaluated" until every control has a passing test
      And trace gives a risk with each controlling requirement, and a requirement with the risks it controls
      And the shapes block exactly the risks the release gate blocks, case by case
      When the risk policy is malformed
      Then the gate blocks, and the shapes block every risk with its message
      When a risk has no id
      Then the gate blocks it, and so do the shapes, on a stand-in node
      When a finding about the register has no risk to carry it
      Then the gate blocks, and so do the shapes, on a stand-in node for the register

    # graph.allium, surface ExploreGraph, guarantee RiskLinkedToItsPolicy
    @allium-guarantee.RiskLinkedToItsPolicy
    @item-06-012
    Scenario: A risk links to the document of the policy it was evaluated against
      Given a risk register and a document declaring the risk policy
      When the graph is built
      Then each risk links to the policy's document
      And without a policy, a risk links to none

  Rule: Each controlled document carries who landed its latest change

    # graph.allium, surface ExploreGraph, guarantee LandingCommitRecorded
    @allium-guarantee.LandingCommitRecorded
    @item-06-011
    Scenario: A document's latest change is recorded with the commit that landed it on the default branch
      Given a change to SDS-1 on a feature branch, which goes on after the change while main moves on
      When someone else merges the branch into main, and later work lands on main
      Then SDS-1's latest change is authored on the branch and landed by the merge, with the merge's author
      And the landing is still the merge, not the newest commit
      When a change is on a branch that has not landed
      Then the document has no landing facts and a warning names it
      When the latest change is a direct commit on main
      Then it landed in itself, by its own author, and nothing warns
      And every controlled document has its commit and landing, the V&V plan as well as the design documents
      And in a repository with no remote whose one branch is neither main nor master, that branch is the default

  Rule: A run carries what it recorded, and nothing the record already holds

    # graph.allium, surface ExploreGraph, guarantee StepsAndAttachmentsCarried
    @allium-guarantee.StepsAndAttachmentsCarried
    @item-06-013
    Scenario: A run's steps and attachments are in the graph and in trace
      Given an Allure result with nested steps and attachments on the run and on a step
      When the graph is built
      Then the run carries its steps in order and nested, each with its name and status
      And its attachments, on the run and on the step, each with its name, media type and file
      And trace lists them with the run, nested and ordered

    # graph.allium, surface ExploreGraph, guarantee OnlyDesignControlFactsProjected
    @allium-guarantee.OnlyDesignControlFactsProjected
    @item-06-014
    Scenario: A result is projected in full, but not what claims nothing about design controls
      Given a failed Allure result with a uuid, a full name, start and stop times, a status message and trace, parameters, labels and links, and a container with fixtures
      When the graph is built
      Then the run carries its uuid, name and times, its status message and trace, and its parameters as name and value
      And its labels are not nodes, its links, a test case per history id and the container fixtures are not projected
      And its item tag and output labels still link the run to its requirement and its code

  Rule: Bounded contexts and documents link to each other from the record

    # graph.allium, surface ExploreGraph, guarantee ContextsLinkedToTheirDocuments
    @allium-guarantee.ContextsLinkedToTheirDocuments
    @item-06-015
    Scenario: Contexts link to the documents that declare them, and documents to the ones they reference
      Given design documents whose frontmatter declares contexts with their part and references other documents
      When the graph is built
      Then each bounded context links to the document whose contexts frontmatter declares it, with its part
      And each controlled document links to the controlled documents its references frontmatter names
      And the traceability matrix template, an output, is not projected
      And once any document declares contexts, a shape warns on a context no document declares
      And validation fails on a reference to a document the record does not hold
      And a context may be declared by id alone, and a single reference may be a string

  Rule: Evidence is tied to the version of the product it tested

    # graph.allium, surface ExploreGraph, guarantee EvidenceTiedToTheProductCommit
    @allium-guarantee.EvidenceTiedToTheProductCommit
    @item-06-016
    Scenario: Each run links to the commit it tested, and a run of another commit than the product's is stale
      Given a record at commit HEAD, and runs labelled with HEAD (one clean, one dirty), with an older commit, and with none
      When the graph is built and validated
      Then each run links to the commit its commit label names
      And the record carries the commit it was built at and the product's commit, its own
      And the run of uncommitted changes is marked and the clean one is not
      And a shape warns on the run tied to no commit
      And a shape warns on the run of the older commit: another commit than the product's
      When the spec lives in a repository of its own
      Then the product's commit is that repository's, the record's stays its own
      And a run of the product's commit is current, and a run of the record's commit is stale
