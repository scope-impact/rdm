// The verification report (DI-64). RDM writes report.json beside this file;
// every value from the record or a test reaches the page as text, never as
// markup, so nothing a test printed can change the document.
#let d = json("report.json")

#set document(title: d.title)
#set text(size: 9pt)
#set par(justify: false)
#set page(
  paper: "a4",
  margin: (x: 1.8cm, y: 2cm),
  header: context {
    set text(7.5pt, fill: luma(90))
    [#d.title #h(1fr) results sha256 #raw(d.results_sha256.slice(0, 16))]
  },
  footer: context {
    set text(7.5pt, fill: luma(90))
    [RDM #d.rdm_version #h(1fr) #counter(page).display("1 / 1", both: true)]
  },
)
#show raw.where(block: true): set text(7.5pt)
#show link: set text(fill: blue.darken(30%))

#let tone(s) = if s in ("passed", "verified") { green.darken(35%) } else if s in ("failed", "broken") {
  red.darken(25%)
} else { luma(80) }
#let badge(s) = box(inset: (x: 3pt, y: 1.5pt), radius: 2pt, fill: tone(s).lighten(85%),
  text(7.5pt, fill: tone(s), weight: "bold", upper(s)))
#let either(value, fallback) = if value == none { text(fill: luma(100), fallback) } else { value }
#let field-table(..rows) = table(
  columns: (auto, 1fr), stroke: 0.4pt + luma(200), inset: 4pt,
  ..rows.pos().map(((k, v)) => (text(weight: "bold", k), v)).flatten(),
)
#let code(s) = block(fill: luma(246), inset: 5pt, radius: 2pt, width: 100%, raw(s, block: true))

#let attachment(a) = block(breakable: true, above: 4pt, below: 6pt, {
  text(weight: "bold", a.name)
  text(fill: luma(90), [ (#a.type#if a.sha256 != none [, sha256 #raw(a.sha256)])])
  if a.kind == "text" { code(a.text) }
  else if a.kind == "image" { block(image("attachments/" + a.source, width: 85%)) }
  else if a.kind == "file" { linebreak(); text(fill: luma(90), [file #raw(a.source), not shown]) }
  else { linebreak(); text(fill: red.darken(25%), [#raw(a.source): not found in the results]) }
})

#let failure(message, trace) = {
  if message != none { block(text(fill: red.darken(25%), message)) }
  if trace != none { code(trace) }
}

#let step(s, depth) = {
  block(above: 3pt, below: 3pt, pad(left: depth * 12pt, [#badge(s.status) #h(3pt) #s.name]))
  pad(left: depth * 12pt + 12pt, {
    failure(s.message, s.trace)
    for a in s.attachments { attachment(a) }
  })
  for child in s.steps { step(child, depth + 1) }
}

= #d.title

#field-table(
  ("Generated", d.generated_at),
  ("RDM version", d.rdm_version),
  ("Results", raw(d.results_dir)),
  ("Results SHA-256", raw(d.results_sha256)),
  ("Commits tested", either(if d.commits.len() > 0 { d.commits.map(raw).join(", ") }, "not recorded")),
  ("Worktree", if d.dirty [uncommitted changes in at least one run] else [clean in every run]),
)

== Summary

#table(
  columns: 5, stroke: 0.4pt + luma(200), inset: 4pt,
  ..("Verified", "Failed", "Untested", "Design inputs", "Allure results").map(h => text(weight: "bold", h)),
  ..(d.summary.verified, d.summary.failed, d.summary.untested, d.summary.total, d.summary.results_found).map(str),
)

#table(
  columns: (auto, auto, 1fr, auto), stroke: 0.4pt + luma(200), inset: 4pt,
  ..("Design input", "Status", "Context", "Runs").map(h => text(weight: "bold", h)),
  ..d.design_inputs.map(di => (di.id, badge(di.status), di.context, str(di.runs.len()))).flatten(),
)

#if d.orphans.len() > 0 [
  == Orphan tags
  Tests tagged with ids no design document declares: #d.orphans.join(", ")
]

#for di in d.design_inputs {
  pagebreak(weak: true)
  heading(level: 2, [#di.id #h(4pt) #badge(di.status)])
  block(di.text)
  field-table(
    ("Context", di.context),
    ("User needs", either(if di.traces_to.len() > 0 { di.traces_to.join(", ") }, "none")),
    ("Outputs", either(if di.outputs.len() > 0 { di.outputs.map(raw).join(", ") }, "none")),
  )
  if di.runs.len() == 0 {
    block(text(fill: red.darken(25%), [No run of a test tagged #di.id.]))
  }
  for r in di.runs {
    heading(level: 3, [#r.name #h(4pt) #badge(r.status)])
    field-table(
      ("Test", raw(r.full_name)),
      ("Commit", either(if r.commit != none { raw(r.commit) }, "not recorded")),
      ("Worktree", if r.dirty [uncommitted changes] else [clean]),
      ("Started", either(r.start, "not recorded")),
      ("Stopped", either(r.stop, "not recorded")),
      ("Labels", r.labels.map(l => [#l.name: #l.value]).join(linebreak())),
      ("Links", either(if r.links.len() > 0 { r.links.map(l => link(l.url, l.name)).join(linebreak()) }, "none")),
    )
    failure(r.message, r.trace)
    if r.steps.len() > 0 {
      block(above: 6pt, text(weight: "bold", "Steps"))
      for s in r.steps { step(s, 0) }
    }
    if r.attachments.len() > 0 {
      block(above: 6pt, text(weight: "bold", "Attachments"))
      for a in r.attachments { attachment(a) }
    }
  }
}
