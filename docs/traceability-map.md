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

<link rel="stylesheet" href="../stylesheets/traceability-map.css">
<div class="rdm-map" data-src="../assets/traceability-map.json">
  <div class="counts" id="counts"><span class="meta">tests run at <code id="commit"></code></span></div>
  <div class="bar">
    <div class="tabs" role="tablist" aria-label="View">
      <button role="tab" id="tabComp" aria-selected="true">Components</button>
      <button role="tab" id="tabMap" aria-selected="false">Trace map</button>
    </div>
    <label for="find">Find</label>
    <input type="search" id="find" placeholder="DI-63, UN-017, Release gate…" autocomplete="off">
    <div class="chips" id="chips" role="group" aria-label="Show a bounded context's trace"></div>
  </div>
  <div class="work">
    <div class="stage">
      <div id="cy" role="img" aria-label="Traceability graph: user needs and risks, design inputs, tests, components in their bounded contexts"></div>
      <div class="lanes" id="lanes"></div>
      <div class="crumb" id="crumb" hidden><button id="back">← Back</button><span>Zoomed into <b id="crumbName"></b></span></div>
      <div class="ctl"><button id="fit">Fit all</button><button id="fitSel">Fit selection</button><button id="full">Full screen</button></div>
    </div>
    <aside id="panel" aria-live="polite"></aside>
  </div>
</div>
<script src="https://cdn.jsdelivr.net/npm/cytoscape@3.34.3/dist/cytoscape.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/elkjs@0.12.0/lib/elk.bundled.js"></script>
<script src="https://cdn.jsdelivr.net/npm/cytoscape-elk@2.3.0/dist/cytoscape-elk.js"></script>
<script src="../javascripts/traceability-map.js"></script>
