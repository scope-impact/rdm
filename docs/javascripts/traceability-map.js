// The traceability map on docs/traceability-map.md: the record's graph, drawn with
// Cytoscape.js and ELK. Its data, assets/traceability-map.json, is written on every
// docs build by docs/_hooks/traceability_map.py from SPARQL over the graph.
(async () => {
  const root = document.querySelector(".rdm-map");
  if (!root) return;
  let DATA;
  try {
    DATA = await (await fetch(root.dataset.src)).json();
  } catch (error) {
    DATA = {error: String(error)};
  }
  if (DATA.error) {
    root.textContent = "The traceability map was not generated: " + DATA.error;
    root.classList.add("rdm-map--none");
    return;
  }
  const KIND = {need: "User need", risk: "Risk", input: "Design input", test: "Tagged test", component: "Component", context: "Bounded context", other: "Element"};
  const ORDER = ["need", "risk", "input", "test", "component", "context"];
  const LANES = [["need", "Needs · risks"], ["input", "Design inputs"], ["test", "Tests"], ["component", "Components in contexts"]];
  const PART = {need: 0, risk: 0, input: 1, test: 2, component: 3, context: 3, other: 4};
  const FLOW = new Set(["traces to", "controlled by", "verifies", "exercises"]);
  const byId = new Map(DATA.nodes.map(n => [n.id, n]));
  const ctxName = iri => (iri || "").split("context/").pop();
  const testName = l => l.split("::").pop();
  const shortLabel = n => n.kind === "test" ? testName(n.label).replace(/^test_/, "").replace(/_/g, " ") : n.label;
  const exercised = new Set(DATA.edges.filter(e => e.rel === "exercises").map(e => e.t));
  const gaps = DATA.nodes.filter(n => n.kind === "component" && !exercised.has(n.id));
  const elLabel = iri => (byId.get(iri) || {}).label || (DATA.elements[iri] || {}).label || iri.split("/").pop();

  // Theme: Cytoscape styles take values, not CSS variables, so read the tokens and restyle on change.
  const tok = () => { const s = getComputedStyle(root); const g = k => s.getPropertyValue("--" + k).trim();
    const o = Object.fromEntries(["bg", "surface", "sunk", "fg", "muted", "line", "edge", "need", "risk", "input", "test", "component", "context", "other",
                                    "body", "mono", "lime", "nexus", "maroon", "hilite", "white", "charcoal"].map(k => [k, g(k)]));
    o.limeInk = g("lime-ink"); o.onMaroon = g("on-maroon");
    // The canvas cannot draw transparent tints, so each is mixed with the page
    // colour into the solid colour it shows as (still only the palette's colours, combined).
    const rgb = s => s.startsWith("#") ? [1, 3, 5].map(i => parseInt(s.slice(i, i + 2), 16)) : s.match(/[\d.]+/g).slice(0, 3).map(Number);
    const base = rgb(o.bg);
    for (const k in o) {
      const m = /^rgba\(([^)]+)\)$/.exec(o[k]); if (!m) continue;
      const [r, g2, b, a] = m[1].split(",").map(Number);
      o[k] = "#" + [r, g2, b].map((c, i) => Math.round(c * a + base[i] * (1 - a)).toString(16).padStart(2, "0")).join("");
    }
    o.inputInk = g("input-ink");
    return o; };
  function style(t) {
    return [
      {selector: "node", style: {"shape": "round-rectangle", "label": "data(label)", "width": "label", "height": 18, "padding": "5px",
        "font-family": t.mono, "font-size": 12, "color": t.fg, "text-valign": "center", "text-halign": "center",
        "background-color": t.surface, "border-width": 1.3, "border-color": e => t[e.data("kind")] || t.other,
        "text-wrap": "ellipsis", "text-max-width": 190, "transition-property": "opacity", "transition-duration": 120}},
      {selector: "node[kind = 'test']", style: {"font-family": t.body, "font-size": 12, "background-color": t.lime, "color": t.limeInk, "border-color": t.lime}},
      {selector: "node[kind = 'risk']", style: {"shape": "cut-rectangle", "background-color": t.maroon, "border-color": t.maroon, "color": t.onMaroon}},
      {selector: "node[kind = 'need']", style: {"shape": "round-tag"}},
      {selector: "node[kind = 'context']", style: {"shape": "round-rectangle", "text-valign": "top", "text-halign": "left", "text-margin-y": -6,
        "text-margin-x": 0, "font-family": t.body, "font-size": 15, "font-weight": 600, "color": t.context, "background-color": t.sunk, "background-opacity": 1,
        "border-style": "solid", "border-width": 1, "border-color": t.line, "padding": "12px", "text-max-width": 400}},
      {selector: "node.gap", style: {"border-style": "dashed"}},
      {selector: "node.big", style: {"font-size": 15, "font-family": t.body, "font-weight": 600, "height": 30, "padding": "10px", "border-width": 2.4}},
      {selector: "node[kind = 'other']", style: {"border-style": "dotted", "color": t.muted}},
      {selector: "edge", style: {"width": 1, "line-color": t.edge, "curve-style": "bezier", "target-arrow-shape": "triangle", "target-arrow-color": t.edge,
        "arrow-scale": 0.55, "opacity": 0.6, "transition-property": "opacity", "transition-duration": 120}},
      {selector: "edge[desc]", style: {"label": "data(desc)", "font-family": t.body, "font-size": 10, "color": t.muted, "text-rotation": "autorotate",
        "text-background-color": t.surface, "text-background-opacity": 1, "text-background-padding": "2px", "opacity": 0.9}},
      {selector: "edge.undeclared", style: {"line-color": t.maroon, "target-arrow-color": t.maroon, "line-style": "dashed", "width": 1.8, "opacity": 0.95}},
      {selector: ".faded", style: {"opacity": 0.28}},
      {selector: "node.card", style: {"text-wrap": "wrap", "text-max-width": 168, "width": 172, "height": 40, "padding": "4px", "font-family": t.body,
        "font-size": 13, "line-height": 1.35, "border-width": 1.6}},
      {selector: "edge.rel", style: {"opacity": 0.12, "curve-style": "bezier", "arrow-scale": 0.8}},
      {selector: "edge.undeclared", style: {"opacity": 0.6}},
      {selector: "edge.lit", style: {"line-color": t.hilite, "target-arrow-color": t.hilite, "width": 1.8, "opacity": 1}},
      {selector: "node.hit", style: {"border-width": 3, "border-color": t.hilite, "font-weight": 600}},
    ];
  }

  cytoscape.use(cytoscapeElk);
  const cy = cytoscape({container: document.getElementById("cy"), wheelSensitivity: 0.25, minZoom: 0.05, maxZoom: 3, boxSelectionEnabled: false});
  let gridPos = {};
  const elk = (parts, wide = false) => wide === "components" ? {name: "preset", fit: false, animate: false, positions: n => gridPos[n.id()]} : ({name: "elk", nodeDimensionsIncludeLabels: true, fit: false, animate: false,
    elk: {"algorithm": "layered", "elk.direction": "RIGHT", "elk.hierarchyHandling": "INCLUDE_CHILDREN", "elk.partitioning.activate": true,
          "elk.layered.spacing.nodeNodeBetweenLayers": wide ? 230 : 90, "elk.spacing.nodeNode": wide ? 22 : 5, "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
          "elk.layered.crossingMinimization.strategy": "LAYER_SWEEP", "elk.spacing.edgeNode": 8},
    nodeLayoutOptions: n => ({"elk.partitioning.partition": parts(n)})});

  // The whole map: every need, risk, input and test; components inside their bounded contexts.
  function mapElements() {
    const els = DATA.nodes.map(n => ({data: {id: n.id, label: shortLabel(n), kind: n.kind, parent: n.kind === "component" ? n.context : undefined},
                                      classes: n.kind === "component" && !exercised.has(n.id) ? "gap" : ""}));
    DATA.edges.filter(e => FLOW.has(e.rel)).forEach((e, i) => {
      const [s, t] = e.rel === "traces to" || e.rel === "verifies" ? [e.t, e.s] : [e.s, e.t];  // always left to right
      els.push({data: {id: "f" + i, source: s, target: t, rel: e.rel}});
    });
    return els;
  }

  let mode = "map", selected = null, focusEls = null;
  const lanes = document.getElementById("lanes");
  function setLanes(list) {
    const t = tok(); lanes.innerHTML = "";
    list.forEach(([k, text]) => { const s = document.createElement("span"); s.className = "lane"; s.textContent = text; s.style.color = (k === "input" ? t.inputInk : t[k]) || t.other;
      if (k === "test") { s.style.background = t.lime; s.style.color = t.limeInk; }  // Lime is a fill, never text on Ivory
      lanes.appendChild(s); });
  }
  function load(els, parts, after, wide = false) {
    cy.elements().remove(); cy.add(els); cy.style(style(tok()));
    const l = cy.layout(elk(parts, wide)); l.one("layoutstop", after); l.run();
  }
  function showMap(then) {
    mode = "map"; backTo = "map"; setTab("map"); document.getElementById("crumb").hidden = true;
    setLanes(LANES);
    load(mapElements(), n => PART[n.data("kind")], () => { window.__ready = true; then && then(); });
  }

  // The components view: bounded contexts holding their components, the declared C4
  // relationships between components, and how many design inputs and tests reach each.
  let fullMap = null;
  function reachOf(id) {
    fullMap ||= cytoscape({headless: true, elements: mapElements()});
    const up = fullMap.getElementById(id).predecessors().nodes();
    return {inputs: up.filter("[kind = 'input']").length, tests: up.filter("[kind = 'test']").length};
  }
  let backTo = "components";
  function showComponents(then) {
    mode = "components"; backTo = "components"; setTab("components");
    document.getElementById("crumb").hidden = true;
    setLanes([["component", "Components in their bounded contexts"], ["muted", "Lines: declared C4 relationships · maroon dashed: an import nothing declares"]]);
    const comps = DATA.nodes.filter(n => n.kind === "component"), ids = new Set(comps.map(n => n.id));
    const els = DATA.nodes.filter(n => n.kind === "context").map(n => ({data: {id: n.id, label: n.label, kind: "context"}}));
    comps.forEach(n => {
      const r = reachOf(n.id);
      const sub = r.tests ? `${r.inputs} input${r.inputs === 1 ? "" : "s"} · ${r.tests} test${r.tests === 1 ? "" : "s"}` : "no test reaches it";
      els.push({data: {id: n.id, label: `${n.label}\n${sub}`, kind: "component", parent: n.context}, classes: "card" + (exercised.has(n.id) ? "" : " gap")});
    });
    // Pack the contexts into rows of boxes; each holds its components in a small grid.
    const CWID = 200, CHGT = 62, GAPX = 70, GAPY = 90, ROWMAX = 1900;
    const order = ["specification", "test_evidence", "release", "publishing", "graph", "architecture", "risk", "compliance"];
    const ctxs = DATA.nodes.filter(n => n.kind === "context").sort((a, b) => order.indexOf(a.label) - order.indexOf(b.label));
    gridPos = {}; let x = 0, y = 0, rowH = 0;
    ctxs.forEach(cx => {
      const members = comps.filter(n => n.context === cx.id).sort((a, b) => a.label.localeCompare(b.label));
      const cols = Math.max(1, Math.min(4, Math.ceil(Math.sqrt(members.length * 1.4))));
      const w = cols * CWID, h = Math.ceil(members.length / cols) * CHGT;
      if (x > 0 && x + w > ROWMAX) { x = 0; y += rowH + GAPY; rowH = 0; }
      members.forEach((n, k) => { gridPos[n.id] = {x: x + (k % cols) * CWID + CWID / 2, y: y + Math.floor(k / cols) * CHGT + CHGT / 2}; });
      x += w + GAPX; rowH = Math.max(rowH, h);
    });
    const declared = new Set();
    DATA.rels.filter(r => ids.has(r.s) && ids.has(r.t)).forEach((r, i) => {
      declared.add(r.s + ">" + r.t);
      els.push({data: {id: "r" + i, source: r.s, target: r.t, rel: r.d || ""}, classes: "rel"});
    });
    DATA.deps.filter(d => ids.has(d.s) && ids.has(d.t) && !declared.has(d.s + ">" + d.t)).forEach((d, i) =>
      els.push({data: {id: "u" + i, source: d.s, target: d.t}, classes: "undeclared"}));
    load(els, () => 0, () => { cy.fit(cy.elements(), 16); focusEls = cy.elements(); window.__ready = true; then && then(); }, "components");
  }
  function selectComponent(id) {
    const n = cy.getElementById(id); if (n.empty()) return;
    selected = id; pressChips(null);
    const near = n.closedNeighborhood();
    cy.elements().removeClass("lit hit").addClass("faded");
    near.union(near.parents()).removeClass("faded"); near.edges().addClass("lit"); n.addClass("hit");
    focusEls = near;
    cy.animate({fit: {eles: near.union(near.parents()), padding: 48}, duration: 280});
    renderPanel(byId.get(id), cytoscape({headless: true, elements: mapElements()}).getElementById(id).predecessors().union(near));
  }
  function setTab(which) {
    document.getElementById("tabComp").setAttribute("aria-selected", String(which === "components"));
    document.getElementById("tabMap").setAttribute("aria-selected", String(which === "map"));
  }

  // A trace: everything upstream and downstream of a node; a context brings its components.
  function traceOf(n) {
    let base = n;
    if (n.data("kind") === "context") base = n.union(n.children());
    return base.union(base.predecessors()).union(base.successors());
  }
  function highlight(n, fit = true) {
    const tr = traceOf(n);
    cy.elements().removeClass("lit hit").addClass("faded");
    tr.union(tr.parents()).removeClass("faded");
    tr.edges().addClass("lit"); n.addClass("hit");
    focusEls = tr;
    if (fit) cy.animate({fit: {eles: tr, padding: 48}, duration: 280});
    return tr;
  }
  function clearHighlight() { cy.elements().removeClass("faded lit hit"); selected = null; focusEls = null; pressChips(null); }

  // Detail panel
  const panel = document.getElementById("panel");
  const add = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; panel.appendChild(e); return e; };
  function facts(pairs) { const dl = add("dl", "facts"); pairs.filter(p => p[1]).forEach(([k, v]) => { const dt = document.createElement("dt"); dt.textContent = k; const dd = document.createElement("dd"); dd.textContent = v; dl.append(dt, dd); }); }
  function linkList(title, nodes) {
    if (!nodes.length) return;
    const s = add("div", "sect"); const h = document.createElement("h3"); h.textContent = title; s.appendChild(h);
    const ul = document.createElement("ul"); ul.className = "links"; s.appendChild(ul);
    nodes.sort((a, b) => a.label.localeCompare(b.label, undefined, {numeric: true})).forEach(o => {
      const li = document.createElement("li"), b = document.createElement("button");
      b.textContent = o.kind === "test" ? testName(o.label) : o.label; b.addEventListener("click", () => select(o.id)); li.appendChild(b); ul.appendChild(li);
    });
  }
  function listBox(title, items, empty) {
    const s = add("div", "sect"); const h = document.createElement("h3"); h.textContent = title; s.appendChild(h);
    if (!items.length) { const p = document.createElement("p"); p.className = "hint"; p.textContent = empty; s.appendChild(p); return; }
    const ul = document.createElement("ul"); ul.className = "files"; s.appendChild(ul);
    items.forEach(f => { const li = document.createElement("li"); li.textContent = f; ul.appendChild(li); });
  }
  function renderPanel(n, tr) {
    panel.innerHTML = "";
    const t = tok();
    add("div", "kind", KIND[n.kind]).style.color = n.kind === "input" ? t.inputInk : t[n.kind];
    add("div", "pid", n.kind === "test" ? testName(n.label) : n.label);
    if (n.kind === "test") add("div", "hint", n.label.split("::")[0]);
    if (n.text) add("p", "ptext", n.text);
    if (n.kind === "input") facts([["Owned by", ctxName(n.owner)]]);
    if (n.kind === "test") facts([["Last run", n.status]]);
    if (n.kind === "risk") facts([["Category", n.category], ["Level", n.level], ["Residual", n.decision]]);
    if (n.kind === "component") {
      facts([["Context", ctxName(n.context)], ["Container", n.container], ["Code", n.code], ["Tested", exercised.has(n.id) ? "yes" : "no test exercises it"]]);
      const z = add("button", "act", "Zoom into this component"); z.addEventListener("click", () => zoomInto(n.id));
    }
    const reach = {}; tr.nodes().forEach(m => { const k = m.data("kind"); if (m.id() !== n.id && k) reach[k] = (reach[k] || 0) + 1; });
    add("p", "hint", "Its trace reaches " + (ORDER.filter(k => reach[k]).map(k => `${reach[k]} ${KIND[k].toLowerCase()}${reach[k] > 1 ? "s" : ""}`).join(", ") || "nothing else") + ".");
    const groups = {};
    DATA.edges.forEach(e => {
      const other = e.s === n.id ? e.t : e.t === n.id ? e.s : null; if (!other) return;
      const o = byId.get(other); (groups[`${e.rel} · ${KIND[o.kind].toLowerCase()}`] ||= []).push(o);
    });
    Object.keys(groups).sort().forEach(k => linkList(k, groups[k]));
  }
  function pressChips(id) { root.querySelectorAll("button.chip").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.id === id))); }
  function select(id, fit = true) {
    const go = () => {
      const ele = cy.getElementById(id); if (ele.empty()) return;
      selected = id; pressChips(id);
      const tr = highlight(ele, fit); renderPanel(byId.get(id), tr);
    };
    if (mode === "components" && (byId.get(id) || {}).kind === "component") return selectComponent(id);
    mode === "map" ? go() : showMap(go);
  }

  // Zoom into one component: what reaches it, and what it calls and is called by, with the imports behind each.
  function zoomInto(id) {
    const c = byId.get(id); mode = "zoom"; selected = id;
    const full = cytoscape({headless: true, elements: mapElements()});
    const tr = full.getElementById(id).predecessors().union(full.getElementById(id));
    const els = [];
    tr.nodes().forEach(n => els.push({data: {id: n.id(), label: n.data("label"), kind: n.data("kind")}, classes: n.id() === id ? "big" : ""}));
    tr.edges().forEach(e => els.push({data: {id: e.id(), source: e.source().id(), target: e.target().id()}}));
    const nb = new Map();
    const touch = o => { if (!nb.has(o)) nb.set(o, {out: [], in: [], dout: [], din: []}); return nb.get(o); };
    DATA.rels.forEach(r => { if (r.s === id) touch(r.t).out.push(r.d || ""); else if (r.t === id) touch(r.s).in.push(r.d || ""); });
    DATA.deps.forEach(d => { if (d.s === id) touch(d.t).dout.push(d); else if (d.t === id) touch(d.s).din.push(d); });
    nb.forEach((v, o) => {
      const known = byId.get(o);
      els.push({data: {id: "nb:" + o, ref: o, label: elLabel(o), kind: known && known.kind === "component" ? "component" : "other"}});
      v.out.forEach((d, i) => els.push({data: {id: `ro${i}:${o}`, source: id, target: "nb:" + o, desc: d}}));
      v.in.forEach((d, i) => els.push({data: {id: `ri${i}:${o}`, source: "nb:" + o, target: id, desc: d}}));
      if (v.dout.length && !v.out.length) els.push({data: {id: `do:${o}`, source: id, target: "nb:" + o, desc: "imports, not declared"}, classes: "undeclared"});
      if (v.din.length && !v.in.length) els.push({data: {id: `di:${o}`, source: "nb:" + o, target: id, desc: "imports, not declared"}, classes: "undeclared"});
    });
    setLanes([["need", "Needs · risks"], ["input", "Design inputs"], ["test", "Tests"], ["component", "Component"], ["other", "What it uses · what uses it"]]);
    document.getElementById("crumb").hidden = false; document.getElementById("crumbName").textContent = c.label;
    load(els, n => n.id() === id ? 3 : (n.id().startsWith("nb:") ? 4 : PART[n.data("kind")]), () => {
      cy.fit(cy.elements(), 40); focusEls = cy.elements();
      renderZoomPanel(c, nb);
    }, true);
  }
  function renderZoomPanel(c, nb) {
    panel.innerHTML = "";
    add("div", "kind", "Component, zoomed in").style.color = tok().component;
    add("div", "pid", c.label);
    if (c.text) add("p", "ptext", c.text);
    facts([["Context", ctxName(c.context)], ["Container", c.container], ["Code", c.code]]);
    const uses = [], usedBy = [];
    nb.forEach((v, o) => {
      const name = elLabel(o);
      if (v.out.length || v.dout.length) uses.push(`${name} — ${v.out.join("; ") || "imports, no relationship declared"}`);
      if (v.in.length || v.din.length) usedBy.push(`${name} — ${v.in.join("; ") || "imports it, no relationship declared"}`);
    });
    listBox(`Uses (${uses.length})`, uses.sort(), "Nothing.");
    listBox(`Used by (${usedBy.length})`, usedBy.sort(), "Nothing.");
    listBox("Files tests exercise", [...(DATA.files[c.id] || [])].sort(), "No test's output label names a file of this component.");
    const imports = [...new Set([...nb.values()].flatMap(v => [...v.dout, ...v.din].map(d => `${d.b} → ${d.i}`)))].sort();
    listBox(`Code imports (${imports.length})`, imports, "No import between this component's code and another's.");
    const back = add("button", "act", backTo === "components" ? "Back to the components" : "Back to the whole map"); back.addEventListener("click", goBack);
  }

  // Interaction
  cy.on("tap", "node", ev => {
    const n = ev.target;
    if (mode === "zoom") {
      const ref = n.data("ref") || n.id();
      const target = byId.get(ref);
      if (target && target.kind === "component" && ref !== selected) return zoomInto(ref);
      if (target) return select(ref);
      return;
    }
    if (mode === "components") { if (n.data("kind") === "component") selectComponent(n.id()); else select(n.id()); return; }
    select(n.id());
  });
  cy.on("dbltap", "node", ev => { const id = ev.target.data("ref") || ev.target.id(); if ((byId.get(id) || {}).kind === "component") zoomInto(id); });
  cy.on("tap", ev => { if (ev.target === cy && mode !== "zoom") { clearHighlight(); renderIntro(); } });
  function goBack() { const id = selected; backTo === "components" ? showComponents(() => selectComponent(id)) : select(id); }
  document.getElementById("back").addEventListener("click", goBack);
  document.getElementById("tabComp").addEventListener("click", () => { clearHighlight(); showComponents(renderIntro); });
  document.getElementById("tabMap").addEventListener("click", () => { clearHighlight(); showMap(() => { cy.fit(cy.elements(), 24); renderIntro(); }); });
  document.getElementById("fit").addEventListener("click", () => cy.animate({fit: {eles: cy.elements(), padding: 24}, duration: 280}));
  document.getElementById("fitSel").addEventListener("click", () => focusEls && cy.animate({fit: {eles: focusEls, padding: 48}, duration: 280}));
  document.getElementById("find").addEventListener("input", ev => {
    const q = ev.target.value.trim().toLowerCase(); if (!q) return;
    const hit = DATA.nodes.find(n => n.label.toLowerCase() === q) || DATA.nodes.find(n => n.label.toLowerCase().includes(q));
    if (hit) select(hit.id);
  });

  // Header: counts, context chips, the coverage gap
  const t0 = tok(), counts = document.getElementById("counts");
  ORDER.forEach(k => {
    const n = DATA.nodes.filter(x => x.kind === k), d = document.createElement("span"); d.className = "count";
    d.innerHTML = '<i class="sq"></i><b></b><span></span>'; const sq = d.querySelector(".sq");
    if (k === "need" || k === "component") { sq.style.border = `2px solid ${t0[k]}`; } else { sq.style.background = t0[k]; }
    d.querySelector("b").textContent = n.length;
    d.querySelector("span").textContent = KIND[k].toLowerCase() + "s" + (k === "test" ? `, ${n.filter(x => x.status === "passed").length} passed` : "");
    counts.appendChild(d);
  });
  document.getElementById("commit").textContent = (DATA.commit || "unknown").slice(0, 7);
  const chips = document.getElementById("chips");
  const LAYER = ["architecture", "risk", "compliance", "specification", "test_evidence", "release", "publishing", "graph"];
  DATA.nodes.filter(n => n.kind === "context").sort((a, b) => LAYER.indexOf(a.label) - LAYER.indexOf(b.label)).forEach(n => {
    const b = document.createElement("button"); b.className = "chip"; b.textContent = n.label; b.dataset.id = n.id;
    b.setAttribute("aria-pressed", "false"); b.addEventListener("click", () => select(n.id)); chips.appendChild(b);
  });
  const gapBtn = document.createElement("button"); gapBtn.className = "chip gap"; gapBtn.dataset.id = "gaps";
  gapBtn.textContent = `${gaps.length} components no test exercises`;
  gapBtn.addEventListener("click", () => {
    const go = () => {
      selected = null; pressChips("gaps");
      const set = cy.collection(gaps.map(g => cy.getElementById(g.id)));
      cy.elements().removeClass("lit hit").addClass("faded"); set.union(set.parents()).removeClass("faded"); set.addClass("hit");
      focusEls = set; cy.animate({fit: {eles: set.union(set.parents()), padding: 48}, duration: 280});
      panel.innerHTML = "";
      add("div", "kind", "Coverage gap").style.color = tok().component;
      add("div", "pid", `${gaps.length} components`);
      add("p", "ptext", "No test's output label names code these components own, so no design input's trace reaches them. Tag the tests that exercise them with @allure.label(\"output\", …).");
      linkList("Components", gaps.slice());
    };
    mode === "zoom" ? showComponents(go) : go();
  });
  chips.appendChild(gapBtn);
  function renderIntro() {
    panel.innerHTML = "";
    add("div", "kind", "How to read it").style.color = tok().muted;
    if (mode === "components") {
      add("p", "ptext", "Every C3 component, inside the bounded context that owns it. Each says how many design inputs and tests reach it; a dashed outline means no test does. Lines are the C4 relationships the workspace declares; a maroon dashed line is an import between components that no relationship declares.");
      add("p", "hint", "Click a component to see what it uses and what uses it. Double-click it, or use its panel button, to zoom into its full trace. Trace map shows the whole chain from user needs.");
    } else {
      add("p", "ptext", "Each user need and risk on the left traces through the design inputs that answer it and the tests that verify them to the components those tests exercise, inside their bounded contexts.");
      add("p", "hint", "Click a node to light its trace. Double-click a component to zoom into it. Click empty space to clear.");
    }
  }


  showComponents(renderIntro);

  // Full screen: the map alone, the canvas and panel resized to the screen.
  const full = document.getElementById("full");
  if (!root.requestFullscreen) full.hidden = true;
  full.addEventListener("click", () => document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen());
  document.addEventListener("fullscreenchange", () => {
    full.textContent = document.fullscreenElement ? "Exit full screen" : "Full screen";
    cy.resize(); cy.animate({fit: {eles: focusEls || cy.elements(), padding: 24}, duration: 200});
  });
})();
