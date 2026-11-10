---
hide:
  - navigation
  - toc
---

# Traceability

RDM's own record, from its knowledge graph, rebuilt on every docs build from a
live run of the acceptance tests. Each user need and risk traces through the
design inputs that answer it to the tests that verify them; a dashed outline
is a design input no passing run verifies. Click a node, or a bounded
context, to trace it.

Every value comes from a SPARQL query over the graph (`rdm graph`), so this is
the traceability matrix, drawn.

<div class="rdm-map" data-src="../assets/traceability-map.json">
--8<-- "docs/_hooks/traceability_map.html"
</div>
