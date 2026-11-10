Feature: An agent reads the record
  An agent, in any harness, can read the record (query it, trace a requirement or need to its tests and results, check it against the gate rules) and cannot change it.

  @item-06-006
  Scenario: RDM shall serve the record to agents as an MCP server over stdio (rdm graph mcp) with four tools: schema (the vocabulary and the predeclared prefixes), query (SPARQL), trace (a requirement, user need or risk with its context, runs and the risks it controls) and validate (the gate shapes' results), each answering from a projection rebuilt from the record on that call.

  @item-06-007
  Scenario: RDM's agent server shall offer no way to change the record or the graph, or to reach the network: query shall accept SELECT, ASK, CONSTRUCT and DESCRIBE and reject SPARQL Update and federated SERVICE calls, trace shall accept only id-shaped input or an id the record declares, and results shall be capped at a row limit, saying when they were cut.
