// ============================================================================
// RDM — Regulatory Documentation Template
// Minimal. Professional. Compliant.
// ============================================================================

// Palette: five colours and white, the same as the docs site's theme
// (docs/stylesheets/rdm-theme.css). Secondary tones are transparent Charcoal,
// never a new hue.
#let charcoal = rgb("#2B2D31")
#let ivory = rgb("#F7F5F3")
#let maroon = rgb("#55111F")
#let nexus = rgb("#383EE9")
#let muted = charcoal.transparentize(38%)
#let tint = charcoal.transparentize(94%)
#let rule-color = charcoal.transparentize(84%)

// Pandoc compatibility
#let horizontalrule = line(length: 100%, stroke: 0.5pt + rule-color)

// Fonts: Nunito Sans for text, JetBrains Mono for headings and code (both in
// the RDM image); the fallbacks keep a document readable without them.
#let body-font = ("Nunito Sans", "Noto Sans")
#let mono-font = ("JetBrains Mono", "DejaVu Sans Mono")

// Document metadata (populated by Pandoc)
#let doc-id = "DOC-000"
#let doc-rev = "1"
#let doc-title = "Document Title"
#let doc-date = datetime.today().display()
#let doc-status = "Draft"

// Main template function
#let template(
  id: doc-id,
  revision: doc-rev,
  title: doc-title,
  date: doc-date,
  status: doc-status,
  body
) = {

  // Document settings
  set document(title: title)

  set page(
    paper: "a4",
    fill: ivory,
    margin: (top: 3cm, bottom: 2.5cm, left: 2.5cm, right: 2.5cm),
    header: context {
      if counter(page).get().first() > 1 [
        #set text(font: mono-font, size: 8pt, fill: muted)
        #id — #title
        #h(1fr)
        Rev #revision
        #v(-0.6em)
        #line(length: 100%, stroke: 0.5pt + rule-color)
      ]
    },
    footer: context {
      set text(font: mono-font, size: 8pt, fill: muted)
      h(1fr)
      [#counter(page).display() / #counter(page).final().first()]
    },
    footer-descent: 1em,
  )

  // Typography
  set text(
    font: body-font,
    size: 10.5pt,
    fill: charcoal,
  )

  set par(
    leading: 0.7em,
    justify: false,
  )

  set strong(delta: 300)

  // Headings: JetBrains Mono, regular weight, tight tracking, Maroon
  set heading(numbering: "1.1")
  show heading: set text(font: mono-font, weight: "regular", tracking: -0.035em, fill: maroon)

  show heading.where(level: 1): it => {
    set text(size: 18pt)
    v(1.5em)
    it
    v(0.75em)
  }

  show heading.where(level: 2): it => {
    set text(size: 14pt)
    v(1em)
    it
    v(0.5em)
  }

  show heading.where(level: 3): it => {
    set text(size: 11pt, fill: charcoal)
    v(0.75em)
    it
    v(0.25em)
  }

  // Links
  show link: it => {
    set text(fill: nexus)
    it
  }

  // Code
  show raw: set text(font: mono-font)

  show raw.where(block: true): it => {
    set text(size: 8.5pt)
    block(
      fill: tint,
      inset: 1em,
      radius: 6pt,
      width: 100%,
      it
    )
  }

  // Inline code
  // highlight, not box: a box cannot break, so a long path or name ran into the
  // next table column
  show raw.where(block: false): it => highlight(fill: tint, extent: 0.15em, radius: 2pt, text(size: 0.92em, it))

  // Identifiers and paths may break after / and _ (test names, file paths in tables)
  show regex("[/_]"): it => it + sym.zws

  // Tables
  set table(
    stroke: 0.5pt + rule-color,
    inset: 7pt,
    align: left + horizon,
  )

  show table.cell.where(y: 0): set text(weight: "bold")
  show table.cell.where(y: 0): set table.cell(fill: tint)
  show table.cell: set align(left)

  // Override Pandoc's centered figure wrapper for tables
  show figure.where(kind: table): set figure(placement: none)
  show figure.where(kind: table): set align(left)
  // A figure does not break across pages by default, so a long table ran over
  // the footer; let it continue on the next page
  show figure.where(kind: table): set block(breakable: true)

  // Lists
  set list(marker: text(fill: maroon)[•])

  // Quotes
  show quote.where(block: true): it => block(
    stroke: (left: 2pt + maroon), inset: (left: 1em, y: 0.4em), text(fill: muted, it.body))

  // ============================================================================
  // Title Page: the title in white on Maroon, like the docs site's header
  // ============================================================================

  page(header: none, footer: none, fill: maroon)[
    #set text(fill: white)
    #v(5cm)

    // Title
    #par(justify: false, leading: 0.35em,
      text(font: mono-font, size: 30pt, weight: "regular", tracking: -0.035em)[#title])

    #v(1.5cm)

    // Metadata
    #grid(
      columns: (7em, auto),
      row-gutter: 0.7em,
      ..(
        ([Document ID], id), ([Revision], revision), ([Date], date), ([Status], status),
      ).map(((k, v)) => (text(size: 10pt, fill: white.transparentize(28%))[#k], text(font: mono-font, size: 10pt)[#v])).flatten()
    )

    #v(1fr)
  ]

  // ============================================================================
  // Table of Contents
  // ============================================================================

  page[
    #outline(
      title: text(font: mono-font, size: 18pt, weight: "regular", tracking: -0.035em, fill: maroon)[Contents],
      indent: 1.5em,
      depth: 3,
    )
  ]

  // ============================================================================
  // Body
  // ============================================================================

  body
}

// Export for Pandoc
#show: template.with(
  $if(id)$id: "$id$",$endif$
  $if(revision)$revision: "$revision$",$endif$
  $if(title)$title: [$title$],$endif$
  $if(date)$date: "$date$",$endif$
  $if(status)$status: "$status$",$endif$
)

$body$
