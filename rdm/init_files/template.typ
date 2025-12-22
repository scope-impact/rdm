// ============================================================================
// RDM — Regulatory Documentation Template
// Minimal. Professional. Compliant.
// ============================================================================

// Pandoc compatibility
#let horizontalrule = line(length: 100%, stroke: 0.5pt + gray)

// Brand colors
#let scope = rgb("#0A2540")
#let accent = rgb("#00D4AA")
#let muted = rgb("#7A8599")
#let light = rgb("#F6F9FC")
#let rule-color = rgb("#E3E8EE")

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
    margin: (top: 3cm, bottom: 2.5cm, left: 2.5cm, right: 2.5cm),
    header: context {
      if counter(page).get().first() > 1 [
        #set text(size: 9pt, fill: muted)
        #id — #title
        #h(1fr)
        Rev #revision
      ]
    },
    footer: context {
      set text(size: 9pt, fill: muted)
      [#counter(page).display() / #counter(page).final().first()]
    },
    footer-descent: 1em,
  )

  // Typography
  set text(
    font: ("Inter Variable", "Noto Sans"),
    size: 11pt,
    fill: scope,
  )

  set par(
    leading: 0.65em,
    justify: true,
  )

  // Headings
  set heading(numbering: "1.1")

  show heading.where(level: 1): it => {
    set text(size: 18pt, weight: "bold", fill: scope)
    v(1.5em)
    it
    v(0.75em)
  }

  show heading.where(level: 2): it => {
    set text(size: 14pt, weight: "bold", fill: scope)
    v(1em)
    it
    v(0.5em)
  }

  show heading.where(level: 3): it => {
    set text(size: 11pt, weight: "bold", fill: muted)
    v(0.75em)
    it
    v(0.25em)
  }

  // Links
  show link: it => {
    set text(fill: accent)
    it
  }

  // Code blocks
  show raw.where(block: true): it => {
    set text(size: 9pt)
    block(
      fill: light,
      inset: 1em,
      radius: 2pt,
      width: 100%,
      it
    )
  }

  // Inline code
  show raw.where(block: false): it => {
    box(
      fill: light,
      inset: (x: 0.3em, y: 0.1em),
      radius: 2pt,
      it
    )
  }

  // Tables
  set table(
    stroke: 0.5pt + rule-color,
    inset: 8pt,
    align: left + horizon,
  )

  show table.cell.where(y: 0): set text(weight: "bold")
  show table.cell.where(y: 0): set table.cell(fill: light)
  show table.cell: set align(left)

  // Override Pandoc's centered figure wrapper for tables
  show figure.where(kind: table): set figure(placement: none)
  show figure.where(kind: table): set align(left)

  // Lists
  set list(marker: text(fill: accent)[•])

  // ============================================================================
  // Title Page
  // ============================================================================

  page(header: none, footer: none)[
    #v(6cm)

    // Title
    #text(size: 32pt, weight: "bold", fill: scope)[#title]

    #v(1.5cm)

    // Metadata
    #grid(
      columns: (6em, auto),
      row-gutter: 0.6em,
      text(size: 10pt, fill: muted)[Document ID], text(size: 10pt)[#id],
      text(size: 10pt, fill: muted)[Revision], text(size: 10pt)[#revision],
      text(size: 10pt, fill: muted)[Date], text(size: 10pt)[#date],
      text(size: 10pt, fill: muted)[Status], text(size: 10pt)[#status],
    )

    #v(1fr)
  ]

  // ============================================================================
  // Table of Contents
  // ============================================================================

  page[
    #outline(
      title: text(size: 18pt, weight: "bold", fill: scope)[Contents],
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
  $if(title)$title: "$title$",$endif$
  $if(date)$date: "$date$",$endif$
  $if(status)$status: "$status$",$endif$
)

$body$
