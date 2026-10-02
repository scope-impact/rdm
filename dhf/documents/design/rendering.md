---
id: SDS-REN-001
kind: design
context: rendering
design_inputs:
  - id: DI-7
    text: "RDM shall render a Markdown template against a supplied data context with Jinja2, so generated data populates the document."
    traces_to: [UN-001]
  - id: DI-8
    text: "RDM shall provide the invert_dependencies, join_to, and md_indent template filters used to build traceability tables."
    traces_to: [UN-001]
  - id: DI-9
    text: "RDM shall post-process rendered Markdown: auto-number sections, expand declared vocabulary, and exclude auditor-only notes from released output."
    traces_to: [UN-001]
  - id: DI-70
    text: "RDM shall render each Mermaid diagram in a controlled document as an image in the document it renders, drawn by Mermaid's own renderer at a pinned version, and shall fail the render, naming the document, when a diagram cannot be drawn."
    traces_to: [UN-001]
realises: [DI-1, DI-4]   # also renders the record-ingest + traceability outputs
---

# Rendering — Software Design

## Design Inputs

This context owns the rendering requirements (the engine that compiles the DHF
from data + templates), all refining UN-001:

- **DI-7 (template rendering)** — render a Markdown template against a supplied
  data context with Jinja2, so a generated data file (e.g. `verification.yml`)
  populates the document.
- **DI-8 (traceability filters)** — provide `invert_dependencies`, `join_to`,
  and `md_indent`, the filters that build traceability tables.
- **DI-9 (markdown post-processing)** — auto-number sections, expand declared
  vocabulary/acronyms, and exclude auditor-only `[[…]]` notes from released
  output.
- **DI-70 (Mermaid diagrams)** — a ` ```mermaid ` block in a controlled document
  (the C4 views among them) becomes an image in the rendered document, drawn by
  Mermaid's own command-line renderer (`@mermaid-js/mermaid-cli`, pinned in the
  RDM image) in a headless browser, so the PDF shows the diagram GitHub and the
  docs site show. Images are named by a hash of the diagram, so an unchanged
  diagram is not drawn again. A diagram the renderer rejects fails the render
  with the document's name, rather than shipping its source as a code block;
  with no renderer installed, the render fails and says how to get one.

It also **realises** the rendering side of inputs owned elsewhere (via
`realises`): **DI-1** (record ingest, owned by `record`) and **DI-4**
(traceability, owned by `verification`).

## Design Outputs

Compiles the DHF from data and templates.

- `rdm/render.py` — two-pass Jinja2 engine; the `invert_dependencies`, `join_to`,
  `md_indent` filters; context keyed by data-file basename, so a generated
  `verification.yml` renders into a traceability matrix.
- `rdm/md_extensions/` — section numbering, vocabulary expansion, auditor-note
  exclusion, Mermaid diagrams as images (`mermaid.py`, DI-70).
- `rdm/init_files/Dockerfile` — the RDM image, with Node and Mermaid's renderer
  at pinned versions (DI-70).
- Output: Markdown → PDF/DOCX via Pandoc/Typst.

Contributes to **UN-001** (compile the DHF from the system of record). The owned
inputs are verified by `@allure.story("DI-7" / "DI-8" / "DI-9" / "DI-70")` acceptance tests;
the realised inputs by their owners' tests plus RDM's existing render unit tests.

## Components (C3)

The components of the `rendering` context, each naming the code that
implements it; a component of another context is shown external, where this
one depends on it.

```mermaid
C4Component
  title Components: rendering
  Container_Boundary(rdm_cli, "rdm") {
    Component(renderer, "Renderer", "Python, Jinja2", "Templates and data to Markdown", $link="rdm/render.py")
    Component(markdown_extensions, "Markdown extensions", "Python", "Section numbers, vocabulary, audit notes, Mermaid diagrams", $link="rdm/md_extensions/")
    Component(utilities, "Utilities", "Python", "Shared YAML and file helpers", $link="rdm/util.py")
  }
  Rel(renderer, utilities, "uses")
  Rel(markdown_extensions, utilities, "uses")
```
