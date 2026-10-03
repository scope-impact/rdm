---
hide:
  - navigation
  - toc
---

# Traceability

RDM's own record, from its knowledge graph, rebuilt on every docs build from a
live run of the acceptance tests. **Components** shows each C3 component
inside its bounded context, with how many design inputs and tests reach it; a
dashed outline means no test does. **Trace map** follows every user need and
risk through design inputs and tests to the components those tests exercise.
Click a node to trace it; double-click a component to zoom into what it uses
and what uses it.

Every value comes from a SPARQL query over the graph (`rdm graph`), so this is
the traceability matrix, drawn.

<div class="rdm-map" data-src="../assets/traceability-map.json">
--8<-- "docs/_hooks/traceability_map.html"
</div>
