# Graph

Slice 06. The record as a read-only RDF graph: projected, stored, queried, served, checked
by the gate shapes, written for AWS Graph Explorer, and read by agents.

- `spec.feature`: the accepted scenarios (`@item-06-001` to `@item-06-016`).
- `allium/graph.allium`: rules GraphBuild, GraphValidate, ExplorerFile and ServeAgent;
  surfaces ExploreGraph and AgentReadsRecord, the needs this context is home to.
  `@item-06-008` covers a guarantee of the risk context's RiskRegisterDesk.
- `event-model/area.edn`: screens `explore-graph` and `agent-reads-record`.
- `event-model/graph.edn`: commands `build`, `validate`, `explorer-file`, `serve-agent`
  and their events.
