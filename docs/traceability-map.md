---
hide:
  - navigation
  - toc
---

# Traceability map

RDM's own record as its knowledge graph holds it, regenerated on every docs
build from the same acceptance run as the [traceability matrix](traceability-matrix.md).
**Components** shows each C3 component inside its bounded context, with how
many design inputs and tests reach it; a dashed outline means no test does.
**Trace map** follows every user need and risk through design inputs and
tests to the components those tests exercise. Click a node to trace it;
double-click a component to zoom into what it uses and what uses it.

Every value on the map comes from a SPARQL query over the graph (`rdm graph`);
the build hook that writes it is `docs/_hooks/traceability_map.py`.

<iframe src="../assets/traceability-map.html" title="RDM traceability map"
        style="width:100%;height:min(92vh,1050px);border:0;border-radius:1.125rem;background:transparent"
        loading="lazy"></iframe>

[Open the map full screen](assets/traceability-map.html)
