// The verification report (DI-64). RDM writes report.json beside this file;
// every value from the record or a test reaches the page as text, never as
// markup, so nothing a test printed can change the document.
//
// Order follows the reader who did not run the tests: what is this and can I
// rely on it; what went wrong; what traces to what; the evidence; how to check.
// Words are the record's (CONTEXT.md): a design input is an acceptance
// criterion, baseline or risk-based; a test verifies it in verification steps;
// a design input is a control *for* a risk, never shown as making it controlled.
#let d = json("report.json")
#let title = "Verification report"

// The palette and fonts of the document template rdm init ships (and of the
// docs site): Charcoal text on Ivory, Maroon JetBrains Mono headings, Nexus links.
#let charcoal = rgb("#2B2D31")
#let maroon = rgb("#55111F")
#let nexus = rgb("#383EE9")
#let mono-font = ("JetBrains Mono", "DejaVu Sans Mono")

#set document(title: title)
#set text(size: 9pt, font: ("Nunito Sans", "Noto Sans"), fill: charcoal)
#show heading: set text(font: mono-font, weight: "regular", tracking: -0.035em, fill: maroon)
#show raw: set text(font: mono-font)
#set par(justify: false)
#set heading(numbering: none)
#set page(
  paper: "a4",
  fill: rgb("#F7F5F3"),
  margin: (x: 1.8cm, y: 2cm),
  header: context {
    set text(7.5pt, fill: charcoal.transparentize(38%))
    [#title · #d.repository #h(1fr) results sha256 #raw(d.results_sha256.slice(0, 16))]
  },
  footer: context {
    set text(7.5pt, fill: charcoal.transparentize(38%))
    [#d.generated_at · RDM #d.rdm_version #h(1fr) page #counter(page).display("1 of 1", both: true)]
  },
)
#show raw.where(block: true): set text(7.5pt)
#show link: set text(fill: nexus)

#let ok = green.darken(35%)
#let bad = red.darken(25%)
#let tone(s) = if s in ("passed", "verified", "acceptable", "accepted", "approved") { ok } else if s in (
  "failed", "broken", "unacceptable", "needs acceptance") { bad } else { charcoal.transparentize(30%) }
#let badge(s) = box(inset: (x: 3pt, y: 1.5pt), radius: 2pt, fill: tone(s).lighten(85%),
  text(7.5pt, fill: tone(s), weight: "bold", upper(s)))
#let muted(body) = text(fill: charcoal.transparentize(38%), body)
#let risk-ref(r) = [#r.id #badge(r.status) residual #badge(r.residual)#if r.status == "proposed" and r.residual != "not evaluated" [ #muted[(on a proposed rating)]]]
#let either(value, fallback) = if value == none { muted(fallback) } else { value }
#let short(sha) = raw(sha.slice(0, 12))
#let field-table(..rows) = table(
  columns: (auto, 1fr), stroke: 0.4pt + charcoal.transparentize(84%), inset: 4pt,
  ..rows.pos().filter(r => r != none).map(((k, v)) => (text(weight: "bold", k), v)).flatten(),
)
#let code(s) = block(fill: charcoal.transparentize(94%), inset: 5pt, radius: 2pt, width: 100%, raw(s, block: true))
#let kb(n) = if n < 1024 [#n B] else [#calc.round(n / 1024, digits: 1) KB]
#let di-label(id) = label("di-" + id)
// An identifier that may wrap: a break opportunity after each underscore and dot.
#let ident(s) = text(font: mono-font, size: 0.9em, s.replace("_", "_\u{200B}").replace(".", ".\u{200B}").replace("/", "/\u{200B}"))
#let first-line(s) = s.split("\n").first()
#let page-of(id) = context link(di-label(id), str(counter(page).at(di-label(id)).first()))

// Attachments the test made are shown; captured output and the copy of the
// requirement are listed by checksum only.
#let attachment(a) = block(breakable: true, above: 4pt, below: 6pt, {
  text(weight: "bold", a.name)
  muted([ · #a.type#if a.sha256 != none [ · #kb(a.bytes) · sha256 #raw(a.sha256)]])
  if a.kind == "text" {
    code(a.text)
    if a.truncated { muted([First lines shown of #a.lines; the full file is #raw(a.source) in the results.]) }
  } else if a.kind == "image" { block(image("attachments/" + a.source, width: 85%)) }
  else if a.kind == "file" { linebreak(); muted([#raw(a.source), not shown]) }
  else if a.kind == "missing" { linebreak(); text(fill: bad, [#raw(a.source): not found in the results]) }
})
#let listed(items) = if items.len() > 0 {
  block(above: 4pt, muted([Not printed: ] + items.map(a => [#a.name (#kb(a.bytes), sha256 #raw(a.sha256.slice(0, 16))…)]).join(", ")))
}
#let shown(items) = items.filter(a => a.kind not in ("captured", "requirement"))
#let hidden(items) = items.filter(a => a.kind in ("captured", "requirement"))

#let failure(message, trace) = {
  if message != none { block(text(fill: bad, message)) }
  if trace != none { code(trace) }
}

#let criterion(s, number) = {
  block(above: 4pt, below: 2pt, grid(columns: (auto, 1fr, auto), gutter: 6pt,
    muted(number), s.name, badge(s.status)))
  pad(left: 14pt, {
    failure(s.message, s.trace)
    for a in shown(s.attachments) { attachment(a) }
    listed(hidden(s.attachments))
  })
  for (i, child) in s.steps.enumerate() { pad(left: 14pt, criterion(child, number + "." + str(i + 1))) }
}

// ---------------------------------------------------------------- 1. identification

= #title

This report is the record of the verification of each design input of
#d.repository against the tests that verify it, generated by RDM from the
design record and the executed test results. It is not edited by hand.
Approval of the record and of the change it verifies is the reviewed pull
request; this report is its evidence.

#let env = d.environment
#field-table(
  ("Repository", d.repository),
  ("Record commit", either(if d.record_commit != none { raw(d.record_commit) }, "unknown")),
  ("Commits tested", either(if d.commits.len() > 0 { d.commits.map(raw).join(linebreak()) }, "not recorded")),
  ("Executed by", either(if d.executor != none {
    let e = d.executor
    let name = e.at("name", default: "executor")
    if "buildUrl" in e { [#name: #link(e.buildUrl, e.at("buildName", default: e.buildUrl))] }
    else { [#name#if "buildName" in e [: #e.buildName]] }
  }, "not recorded (no executor.json in the results)")),
  ("Environment", either(if env.len() > 0 { env.pairs().map(((k, v)) => [#k: #v]).join(linebreak()) },
    "not recorded (no environment.properties in the results)")),
  ("RDM version", d.rdm_version),
  ("Results", [#raw(d.results_dir) · #d.files.len() files]),
  ("Results SHA-256", raw(d.results_sha256)),
  ("Generated", d.generated_at),
)

// ---------------------------------------------------------------- 2. evidence status

== Evidence status

#let grade = if d.release_grade { ok } else { bad }
#block(width: 100%, inset: 8pt, radius: 3pt, stroke: 1pt + grade, fill: grade.lighten(92%), {
  text(11pt, weight: "bold", fill: grade,
    if d.release_grade [Release-grade evidence] else [Not release-grade evidence])
  linebreak()
  if d.release_grade [
    Every design input has a passing run, no run failed, and every run tested the record's commit with no
    uncommitted changes.
  ] else {
    list(..d.reasons)
  }
})

#let reg = d.risk_register
#block(width: 100%, inset: 6pt, radius: 3pt, stroke: 0.6pt + charcoal.transparentize(70%), {
  text(weight: "bold", [Risk register])
  linebreak()
  if reg.risks == 0 [No risks are declared.] else [
    #reg.risks risk(s); acceptability criteria #badge(reg.policy).
    #if reg.proposed > 0 [#text(fill: bad)[#reg.proposed rating(s) are proposals no person has approved.]]
    #if reg.not_evaluated > 0 [#reg.not_evaluated residual(s) not evaluated.]
    A risk-based acceptance criterion counts only once it is verified and its risk's residual is acceptable.
  ]
})

#table(
  columns: 5, stroke: 0.4pt + charcoal.transparentize(84%), inset: 4pt,
  ..("Verified", "Failed", "Untested", "Design inputs", "Test results").map(h => text(weight: "bold", h)),
  ..(d.summary.verified, d.summary.failed, d.summary.untested, d.summary.total, d.summary.results_found).map(str),
)

// ---------------------------------------------------------------- 3. anomalies

== Anomalies

#if d.anomalies.len() == 0 [None: every run passed, and every design input has one.] else {
  table(
    columns: (2fr, auto, 3fr), stroke: 0.4pt + charcoal.transparentize(84%), inset: 4pt,
    ..("Subject", "Kind", "Detail").map(h => text(weight: "bold", h)),
    ..d.anomalies.map(a => (ident(a.subject), badge(a.kind), first-line(a.detail))).flatten(),
  )
}

// ---------------------------------------------------------------- 4. traceability

== Traceability

#table(
  columns: (auto, auto, auto, 1fr, auto, auto), stroke: 0.4pt + charcoal.transparentize(84%), inset: 4pt,
  ..("Design input", "User needs", "Control for", "Tests", "Result", "Page").map(h => text(weight: "bold", h)),
  ..d.design_inputs.map(di => (
    link(di-label(di.id), di.id),
    di.traces_to.join(", "),
    either(if di.control_for.len() > 0 { di.control_for.map(r => r.id).join(", ") }, "—"),
    either(if di.runs.len() > 0 { di.runs.map(r => ident(r.test.split("::").last())).join(linebreak()) }, "none"),
    badge(di.status),
    page-of(di.id),
  )).flatten(),
)

// ---------------------------------------------------------------- 5. evidence

#for di in d.design_inputs {
  pagebreak(weak: true)
  [#heading(level: 2, [#di.id #h(4pt) #badge(di.status)])#di-label(di.id)]
  block(inset: (left: 8pt), stroke: (left: 2pt + maroon), di.text)
  field-table(
    ("Acceptance criterion", if di.control_for.len() > 0 [risk-based: a risk control] else [baseline: from its user needs]),
    ("Bounded context", di.context),
    ("User needs", either(if di.traces_to.len() > 0 { di.traces_to.join(", ") }, "none")),
    if di.control_for.len() > 0 { ("Control for", di.control_for.map(risk-ref).join(linebreak())) },
    ("Verification method", [automated test tagged #di.id]),
    ("Design outputs", either(if di.outputs.len() > 0 { di.outputs.map(raw).join(", ") }, "none declared")),
  )
  if di.runs.len() == 0 { block(text(fill: bad, [No run of a test tagged #di.id.])) }
  for r in di.runs {
    heading(level: 3, [#ident(r.test) #h(4pt) #badge(r.status)])
    field-table(
      ("Run", [#either(r.start, "start not recorded") · #either(r.duration, "duration not recorded")]),
      if r.commit == none { ("Commit", text(fill: bad, "not recorded")) }
      else if r.commit != d.record_commit { ("Commit", text(fill: bad, [#raw(r.commit) (not the record's)])) },
      if r.dirty { ("Worktree", text(fill: bad, "uncommitted changes")) },
      if r.labels.len() > 0 { ("Labels", r.labels.map(l => [#l.name: #l.value]).join(", ")) },
      if r.links.len() > 0 { ("Declared in", r.links.map(l => link(l.url, l.name)).join(linebreak())) },
    )
    failure(r.message, r.trace)
    if r.steps.len() > 0 {
      block(above: 8pt, below: 2pt, text(weight: "bold", "Verification steps"))
      for (i, s) in r.steps.enumerate() { criterion(s, str(i + 1)) }
    }
    if shown(r.attachments).len() > 0 {
      block(above: 8pt, below: 2pt, text(weight: "bold", "Evidence"))
      for a in shown(r.attachments) { attachment(a) }
    }
    listed(hidden(r.attachments))
  }
}

// ---------------------------------------------------------------- 6. how to check

#pagebreak(weak: true)
== Appendix A — result files

Every file the report was built from. The results SHA-256 in the header is the
SHA-256 of these lines, in this order, each `name`, a NUL byte, the file's
SHA-256 in hex, and a newline. Recompute it on the retained evidence bundle to
show this report describes that evidence.

#table(
  columns: (1fr, auto, auto), stroke: 0.4pt + charcoal.transparentize(84%), inset: 3pt,
  ..("File", "Size", "SHA-256").map(h => text(weight: "bold", h)),
  ..d.files.map(f => (text(7pt, ident(f.name)), text(7pt, kb(f.bytes)), text(6.5pt, raw(f.sha256)))).flatten(),
)

== Appendix B — how this report is produced

- A design input is an acceptance criterion: a `shall` requirement on the
  system or one of its bounded contexts. It is *baseline* when it follows from
  its user needs alone, and *risk-based* when it is allocated as a control for a
  risk; a risk-based criterion counts only once it is verified and that risk's
  residual is evaluated acceptable.
- A design input is *verified* when a test tagged with it (`@allure.story`) has
  a passing run and none failed. The test's steps are its verification steps.
- A risk control that is verified is not thereby *effective*: that needs the
  risk evaluated again with an acceptable residual, shown beside each risk. A
  rating no person has approved is shown as a proposal.
- The design inputs, their user needs and contexts, and the risks they control
  are read from the design record at the record commit above; the runs, from
  the Allure results.
- Not repeated per run: the labels this report already shows or that would
  mislead (#d.left_out.shown.join(", "); Allure's severity is not a harm's
  severity), and runner internals (#d.left_out.runner.join(", ")). pytest's
  captured output and the copy of the requirement the test run records are
  listed by checksum, not printed; both are in the results.
- The test that verifies a design input passing does not by itself show that
  it proves the input; that judgement is the reviewer's, in the pull request.
