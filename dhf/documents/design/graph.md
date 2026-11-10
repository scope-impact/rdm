---
id: SDS-GRAPH-001
kind: design
context: graph
---

# Knowledge graph — Software Design

## Purpose

This context owns the knowledge graph: the design record, with its test
runs, git history, risks and checklists, projected into RDF
for people and agents to query, browse and validate. It speaks of
*projection* (one source of the record turned into facts, one named graph
per source), the *vocabulary* (the classes and properties queries and agents
rely on: the published language), and *gate rules* (shapes whose violations
block and whose warnings inform). The graph is a read model:
the Markdown record and the tests stay the only authored source; the graph
is rebuilt from them on demand, never edited, and never the source of a
fact. It is open world: it states only what the record states, so a missing
`rdm:verifies` link means *no tag was found*, never *unverified*;
closed-world judgments are the gate rules' and the coded gates'. `graph`
names it in commands and code.

## Design Outputs

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Projection | Turns each source of the record into its own named graph, de-duplicated and in a stable order; writes sorted N-Quads; replaces the store on each build; answers SPARQL over the store or an in-memory projection; starts the read-only endpoint; adds checklists on request | item-06-001, item-06-002, item-06-003, item-06-008, item-06-009, item-06-011, item-06-012, item-06-013, item-06-014, item-06-015, item-06-016 |
| Vocabulary | The classes and properties of the graph; projected as the ontology graph | item-06-001 |
| Gate shapes | The gate rules as SHACL shapes: violations block exactly what the release gate blocks, warnings inform; plus the checklist data's own shapes | item-06-004, item-06-008, item-06-011, item-06-015, item-06-016 |
| SHACL validation | `rdm graph validate`: runs the shipped and any user-supplied shapes over the union of the named graphs, reports each result most severe first, and fails on any violation | item-06-010, item-06-015 |
| Explorer file | Writes the record as an AWS Graph Explorer file, every record node whatever its IRI, optionally leaving out chosen classes and what hangs only from them | item-06-005 |
| Agent server | `rdm graph mcp`: the schema, query, trace and validate tools for agents, read-only, each answering from a projection rebuilt on that call | item-06-006, item-06-007, item-06-008, item-06-013 |

All of them run in the `rdm` command, and need the optional extra `graph`;
without it every `rdm graph` command exits 2 and names the extra. The
Projection reads other contexts' components (see Dependencies). Each requirement is verified by a test tagged with it in
the acceptance suite.

How the components relate, in the direction of the arrows:

- The **Projection** declares its terms and rules in the Vocabulary, and
  writes the explorer file with the Explorer file, which takes only the
  executions graph's name from the Projection, never a second read of the
  record. It builds the graph store, which the SPARQL endpoint serves
  read-only to AWS Graph Explorer, its default graph the union of the named
  graphs. The endpoint is RDM's own (`rdm graph serve`), reading the store
  afresh for each query, so it serves the last build and refuses what the
  Agent server refuses.
- **SHACL validation** validates the Projection's graph and checks it with
  the Gate shapes.
- The **Agent server** projects the record afresh with the Projection on each
  call; validates with SHACL validation; returns the Vocabulary for
  `schema`; and checks that `trace` input is
  an id with the shared kernel. It never uses the graph store, so an agent
  never reads a stale graph. The agent harness calls it over MCP stdio; RDM's
  own repository registers it for its agents.

The behaviour a reviewer needs to judge the design:

- **Named graphs.** The record graph holds the spec (user needs with their
  text and, from an adapter, actors, surfaces, rules, guarantees and storm
  events; requirements with their rule and source; an adapter's rules; bounded
  contexts) and the controlled documents, each context's declaring document
  and part, and the documents' references; the executions graph
  the Allure results, when given; the git graph each document's latest
  landed commit and the commit the record was built at; the risks graph the
  register, with a finding about the register as a whole on each risk, or on
  a stand-in node for the register when it has none; the checklists and references graphs, on request; and the ontology
  graph the Vocabulary. The traceability matrix template is not projected: it
  is output, not record.
- **Whole-record findings.** What the release gate blocks about the record
  as a whole (no requirement, a result file that cannot be
  read) goes on the record's node as the gate words it,
  and a shape blocks each one, so validation never passes what the gate
  blocks. The executions graph is read with the Allure reader the gates use.
  A declared id links its tests and runs whatever its shape; an undeclared
  tag links only when it is id-shaped.
- **Identity.** Instance IRIs are `urn:dhf:<project>:<kind>/<id>`, the
  project being the repository's name unless another is given, so graphs
  from several repositories merge without collisions; clause, standard and
  checklist IRIs (`urn:rdm:clause:62304:5.6.2`) are project-independent.
- **Vocabulary reuse.** Standard terms are reused where they exist:
  `rdm:DesignInput rdfs:subClassOf oslc_rm:Requirement` (OSLC Requirements
  Management), Dublin Core for identifiers, titles, descriptions and
  references, PROV-O for commits, authors and run times, SKOS for
  checklists. RDM terms (`rdm:UserNeed`, `rdm:BoundedContext`, `rdm:Test`,
  `rdm:TestRun`, `rdm:Risk`, `rdm:tracesTo`, `rdm:ownedBy`,
  `rdm:coversRule`, `rdm:exercises`, …) are minted only where
  none fits.
- **What is refused.** The Agent server's `query`, the SPARQL endpoint and
  `rdm graph query` refuse SPARQL Update, any `SERVICE` call outside
  literals, IRIs and comments (a comment ends at either line break), and
  anything that does not parse as a query, and `FROM` or `FROM NAMED`, which
  would be ignored (a query names a graph with `GRAPH`); a variable or prefix
  named SERVICE is not a call. The Agent server's row limit is capped. Every tool is
  annotated read-only and idempotent. `trace` answers a user need, a design
  input (with its risks, runs, steps, attachments and source files) or a
  risk, and nothing that is neither id-shaped nor declared. SHACL validation
  exits 1 on a violation and 2 on a missing DHF or a shapes or checklist file
  that is missing or not RDF. An `--exclude` class the graph does not have is
  refused. The default branch is origin's HEAD,
  else `main` or `master`, else git's `init.defaultBranch`, else the only
  local branch.

Open questions:

- Whether the shapes replace the coded gates (RDM-004.06); until then the
  acceptance tests hold the two to agreement.

## Dependencies

A read model at the top layer (5) of the dependency rule: it depends on the
contexts below it, and nothing but the composition root depends on it in
code; the release context's gates action runs `rdm graph validate` as a CI
step, not as an import. It projects:

- **specification**, through the Spec reader and the Need reader: needs,
  requirements, an adapter's rules, contexts;
- **test evidence**, through the Allure reader: results and labels, and the
  verified set;
- **risk**, through the Risk register: the register, the policy, the
  residual decision and the release gate's findings;
- **compliance**, through Gap analysis: the checklist reader, the built-in
  checklists and the key matcher;
- **release**, through the Release gate: its findings about the whole
  record, which the Projection puts on the record for the shapes;

and the shared kernel (ids, git history, frontmatter). It does not read
publishing. No dependency breaks the rule.

## Out of scope

Writing back: nothing the graph holds or derives changes the record.
Pushing the graph to an external RDF store, versioning the vocabulary and
gate shapes, and several projects in one graph are not yet in scope (see
the system architecture).
