// The traceability map on docs/traceability-map.md: the record's graph, drawn with
// Cytoscape.js and ELK. Its data, assets/traceability-map.json, is written on every
// docs build by docs/_hooks/traceability.py from SPARQL over the graph.
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
  const KIND = {need: "User need", risk: "Risk", requirement: "Requirement", test: "Tagged test", context: "Bounded context"};
  const ORDER = ["need", "risk", "requirement", "test", "context"];
  const LANES = [["need", "Needs · risks"], ["requirement", "Requirements"], ["test", "Tests"]];
  const PART = {need: 0, risk: 0, requirement: 1, test: 2};
  const FLOW = new Set(["traces to", "controlled by", "verifies"]);
  const byId = new Map(DATA.nodes.map(n => [n.id, n]));
  const ctxName = iri => (iri || "").split("context/").pop();
  const testName = l => l.split("::").pop();
  const shortLabel = n => n.kind === "test" ? testName(n.label).replace(/^test_/, "").replace(/_/g, " ") : n.label;
  // A requirement no passing run verifies is a gap.
  const verified = new Set(DATA.edges.filter(e => e.rel === "verifies" && (byId.get(e.s) || {}).status === "passed").map(e => e.t));
  const gaps = DATA.nodes.filter(n => n.kind === "requirement" && !verified.has(n.id));

  // Theme: Cytoscape styles take values, not CSS variables, so read the tokens and restyle on change.
  const tok = () => { const s = getComputedStyle(root); const g = k => s.getPropertyValue("--" + k).trim();
    const o = Object.fromEntries(["bg", "surface", "sunk", "fg", "muted", "line", "edge", "need", "risk", "requirement", "test", "component", "context", "other",
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
    o.requirementInk = g("requirement-ink");
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
      {selector: "edge", style: {"width": 1, "line-color": t.edge, "curve-style": "bezier", "target-arrow-shape": "triangle", "target-arrow-color": t.edge,
        "arrow-scale": 0.55, "opacity": 0.6, "transition-property": "opacity", "transition-duration": 120}},
      {selector: ".faded", style: {"opacity": 0.28}},
      {selector: "edge.lit", style: {"line-color": t.hilite, "target-arrow-color": t.hilite, "width": 1.8, "opacity": 1}},
      {selector: "node.hit", style: {"border-width": 3, "border-color": t.hilite, "font-weight": 600}},
    ];
  }

  cytoscape.use(cytoscapeElk);
  const cy = cytoscape({container: document.getElementById("cy"), wheelSensitivity: 0.25, minZoom: 0.05, maxZoom: 3, boxSelectionEnabled: false});
  const elk = parts => ({name: "elk", nodeDimensionsIncludeLabels: true, fit: false, animate: false,
    elk: {"algorithm": "layered", "elk.direction": "RIGHT", "elk.partitioning.activate": true,
          "elk.layered.spacing.nodeNodeBetweenLayers": 90, "elk.spacing.nodeNode": 5, "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
          "elk.layered.crossingMinimization.strategy": "LAYER_SWEEP", "elk.spacing.edgeNode": 8},
    nodeLayoutOptions: n => ({"elk.partitioning.partition": parts(n)})});

  // The whole map: every need, risk, requirement and test, left to right.
  function mapElements() {
    const els = DATA.nodes.filter(n => n.kind in PART).map(n => ({data: {id: n.id, label: shortLabel(n), kind: n.kind},
                                      classes: n.kind === "requirement" && !verified.has(n.id) ? "gap" : ""}));
    DATA.edges.filter(e => FLOW.has(e.rel)).forEach((e, i) => {
      const [s, t] = e.rel === "traces to" || e.rel === "verifies" ? [e.t, e.s] : [e.s, e.t];  // always left to right
      els.push({data: {id: "f" + i, source: s, target: t, rel: e.rel}});
    });
    return els;
  }

  let selected = null, focusEls = null;
  const lanes = document.getElementById("lanes");
  function setLanes(list) {
    const t = tok(); lanes.innerHTML = "";
    list.forEach(([k, text]) => { const s = document.createElement("span"); s.className = "lane"; s.textContent = text; s.style.color = (k === "requirement" ? t.requirementInk : t[k]) || t.other;
      if (k === "test") { s.style.background = t.lime; s.style.color = t.limeInk; }  // Lime is a fill, never text on Ivory
      lanes.appendChild(s); });
  }
  function showMap(then) {
    setLanes(LANES);
    cy.elements().remove(); cy.add(mapElements()); cy.style(style(tok()));
    const l = cy.layout(elk(n => PART[n.data("kind")]));
    l.one("layoutstop", () => { cy.fit(cy.elements(), 24); window.__ready = true; then && then(); }); l.run();
  }

  // A trace: everything upstream and downstream of a node; a context brings the requirements it owns.
  function traceOf(id) {
    const owned = DATA.edges.filter(e => e.rel === "owns" && e.s === id).map(e => cy.getElementById(e.t));
    const base = owned.length ? cy.collection(owned) : cy.getElementById(id);
    return base.union(base.predecessors()).union(base.successors());
  }
  function highlight(id) {
    const tr = traceOf(id);
    cy.elements().removeClass("lit hit").addClass("faded");
    tr.removeClass("faded"); tr.edges().addClass("lit"); cy.getElementById(id).addClass("hit");
    focusEls = tr;
    if (tr.nonempty()) cy.animate({fit: {eles: tr, padding: 48}, duration: 280});
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
  function renderPanel(n, tr) {
    panel.innerHTML = "";
    const t = tok();
    add("div", "kind", KIND[n.kind]).style.color = n.kind === "requirement" ? t.requirementInk : t[n.kind];
    add("div", "pid", n.kind === "test" ? testName(n.label) : n.label);
    if (n.kind === "test") add("div", "hint", n.label.split("::")[0]);
    if (n.text) add("p", "ptext", n.text);
    if (n.kind === "requirement") facts([["Owned by", ctxName(n.owner)], ["Verified", verified.has(n.id) ? "yes, by a passing run" : "no passing run"]]);
    if (n.kind === "test") facts([["Last run", n.status]]);
    if (n.kind === "risk") facts([["Category", n.category], ["Level", n.level], ["Residual", n.decision]]);
    const reach = {}; tr.nodes().forEach(m => { const k = m.data("kind"); if (m.id() !== n.id && k) reach[k] = (reach[k] || 0) + 1; });
    add("p", "hint", "Its trace reaches " + (ORDER.filter(k => reach[k]).map(k => `${reach[k]} ${KIND[k].toLowerCase()}${reach[k] > 1 ? "s" : ""}`).join(", ") || "nothing else") + ".");
    const groups = {};
    DATA.edges.forEach(e => {
      const other = e.s === n.id ? e.t : e.t === n.id ? e.s : null; if (!other) return;
      const o = byId.get(other); if (!o) return; (groups[`${e.rel} · ${KIND[o.kind].toLowerCase()}`] ||= []).push(o);
    });
    Object.keys(groups).sort().forEach(k => linkList(k, groups[k]));
  }
  function pressChips(id) { root.querySelectorAll("button.chip").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.id === id))); }
  function select(id) {
    if (!byId.has(id)) return;
    selected = id; pressChips(id);
    renderPanel(byId.get(id), highlight(id));
  }

  // Interaction
  cy.on("tap", "node", ev => select(ev.target.id()));
  cy.on("tap", ev => { if (ev.target === cy) { clearHighlight(); renderIntro(); } });
  document.getElementById("fit").addEventListener("click", () => cy.animate({fit: {eles: cy.elements(), padding: 24}, duration: 280}));
  document.getElementById("fitSel").addEventListener("click", () => focusEls && cy.animate({fit: {eles: focusEls, padding: 48}, duration: 280}));
  document.getElementById("find").addEventListener("input", ev => {
    const q = ev.target.value.trim().toLowerCase(); if (!q) return;
    const hit = DATA.nodes.find(n => n.label.toLowerCase() === q) || DATA.nodes.find(n => n.label.toLowerCase().includes(q));
    if (hit) select(hit.id);
  });

  // Header: counts, context chips, the verification gap
  const t0 = tok(), counts = document.getElementById("counts");
  ORDER.forEach(k => {
    const n = DATA.nodes.filter(x => x.kind === k), d = document.createElement("span"); d.className = "count";
    d.innerHTML = '<i class="sq"></i><b></b><span></span>'; const sq = d.querySelector(".sq");
    if (k === "need" || k === "context") { sq.style.border = `2px solid ${t0[k]}`; } else { sq.style.background = t0[k]; }
    d.querySelector("b").textContent = n.length;
    d.querySelector("span").textContent = KIND[k].toLowerCase() + "s" + (k === "test" ? `, ${n.filter(x => x.status === "passed").length} passed` : "");
    counts.appendChild(d);
  });
  document.getElementById("commit").textContent = (DATA.commit || "unknown").slice(0, 7);
  const chips = document.getElementById("chips");
  DATA.nodes.filter(n => n.kind === "context").sort((a, b) => a.label.localeCompare(b.label)).forEach(n => {
    const b = document.createElement("button"); b.className = "chip"; b.textContent = n.label; b.dataset.id = n.id;
    b.setAttribute("aria-pressed", "false"); b.addEventListener("click", () => select(n.id)); chips.appendChild(b);
  });
  const gapBtn = document.createElement("button"); gapBtn.className = "chip gap"; gapBtn.dataset.id = "gaps";
  gapBtn.textContent = `${gaps.length} requirements no passing run verifies`;
  gapBtn.addEventListener("click", () => {
    selected = null; pressChips("gaps");
    const set = cy.collection(gaps.map(g => cy.getElementById(g.id)));
    cy.elements().removeClass("lit hit").addClass("faded"); set.removeClass("faded"); set.addClass("hit");
    focusEls = set; if (set.nonempty()) cy.animate({fit: {eles: set, padding: 48}, duration: 280});
    panel.innerHTML = "";
    add("div", "kind", "Verification gap").style.color = tok().requirement;
    add("div", "pid", `${gaps.length} requirements`);
    add("p", "ptext", "No passing run of a test that claims these requirements is in the results, so the release gate would block them.");
    linkList("Requirements", gaps.slice());
  });
  chips.appendChild(gapBtn);
  function renderIntro() {
    panel.innerHTML = "";
    add("div", "kind", "How to read it").style.color = tok().muted;
    add("p", "ptext", "Each user need and risk on the left traces through the requirements that address it to the tests that verify them. A dashed outline is a requirement no passing run verifies.");
    add("p", "hint", "Click a node, or a bounded context above, to light its trace. Click empty space to clear.");
  }

  showMap(renderIntro);

  // Full screen: the map alone, the canvas and panel resized to the screen.
  const full = document.getElementById("full");
  if (!root.requestFullscreen) full.hidden = true;
  full.addEventListener("click", () => document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen());
  document.addEventListener("fullscreenchange", () => {
    full.textContent = document.fullscreenElement ? "Exit full screen" : "Full screen";
    cy.resize(); cy.animate({fit: {eles: focusEls || cy.elements(), padding: 24}, duration: 200});
  });
})();
