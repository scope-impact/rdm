---
id: SDS-REN-001
kind: design
context: publishing
# Implements part of inputs other contexts own.
realises: [DI-1, DI-4, DI-30]
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
  - id: DI-16
    text: "RDM shall extract delimited code snippets from source files (RDOC/ENDRDOC), keyed by name, for inclusion in documents."
    traces_to: [UN-001]
  - id: DI-29
    text: "RDM shall generate device-master-record index data from controlled documents' frontmatter, writing one entry per document (id, title, path, revision) to a data file the DMR index renders from."
    traces_to: [UN-012]
  - id: DI-64
    text: "RDM shall render a verification report to PDF from the record and the Allure results it is given, for review by someone who did not run the tests: a header naming the repository, the record's commit, the commits tested, the executor and environment the results record, the RDM version and one SHA-256 over the result files; an evidence status that is release-grade only when every design input has a passing run, no run failed, and every run tested the record's commit with no uncommitted changes, and that otherwise names each reason; the anomalies (failed, broken or skipped runs, and design inputs with no run); a traceability table of user need, design input, the risks it is a control for, its tests and their result; per design input, its text, context, user needs, outputs, whether it is a baseline or a risk-based acceptance criterion, and each risk it is a control for with that risk's status and residual decision, and for each run of a test tagged with it the test's file and function, its result, date and duration, its commit and worktree state where they differ from the header, any failure message and trace, its labels other than those the report already shows, its links, each step as a verification step with its own result, and the attachments the test made (text inline up to a limit, images embedded, any other file named with its SHA-256), with captured output and the copy of the requirement listed by name and SHA-256 only, and the Allure severity label left out; and an appendix listing every result file with its SHA-256. The evidence bundle and the reusable gates workflow shall include the report."
    traces_to: [UN-012]
---

# Publishing — Software Design

## Purpose

Publishing renders the design record into controlled documents: a
*template* (a controlled document's source, with placeholders) filled with
*data* generated from the record, then post-processed Markdown, and from it
PDF and DOCX. It owns the template engine and its filters, the Markdown
post-processing, the code snippets a document embeds, the device-master-record
(DMR) index data and the verification report. Its language is template, data
and rendered document; a rendered document is output, never source. It is a
read model: it reads the record through the contexts below it, never changes
it, and nothing it renders is fed back into it.

## Design Outputs

- **Renderer** (`rdm/publishing/render.py`, `rdm render <template> <config> <data…>`)
  — meets DI-7 and DI-8. A Jinja2 environment with strict undefined values
  (a placeholder with no data fails the render), templates loaded from the
  working directory, and the three filters registered. The data context is
  keyed by each data file's basename (`context_from_data_files` in
  `rdm/kernel/util.py`; two files with one basename are an error), so
  `data/verification.yml` is `verification` in the template. When a
  template reads the first pass's output (`first_pass_output`,
  `rdm/publishing/first_pass_output.py`), it is rendered a second time with that output
  available. The extensions listed under `md_extensions` in the project's
  `config.yml` are loaded, and their filters run over the rendered lines.
- **First-pass output** (`rdm/publishing/first_pass_output.py`) — the words a
  first render produced, which the second pass tests against (DI-9).
- **Markdown extensions** (`rdm/md_extensions/`) — meet DI-9.
  `SectionNumberExtension` prefixes each heading with its section number;
  `VocabularyExtension` gives the template the words of the first-pass
  output (`first_pass_output.words`, `has`, `has_ignore_case`, the
  `present_in` filter), so a glossary or acronym list includes only the
  terms the document uses; `AuditNoteExclusionExtension` removes each
  `[[…]]` note, and the space before it, from the output. The `config.yml`
  that `rdm init` lays down enables only the vocabulary extension.
- **Code snippets** (`rdm/publishing/collect.py`, `rdm collect <files…>`) — meets
  DI-16. A snippet runs from the line holding `RDOC <key>` to the `ENDRDOC`
  in the same column, its lines kept from that column. An empty or repeated
  key in one file, an `ENDRDOC` in another column, or a missing `ENDRDOC` is
  an error naming the file and line. The snippets are written as YAML, a
  data file the Renderer reads like any other.
- **DMR index** (`rdm/publishing/dmr.py`, `rdm story dmr <dir> -o <out.yml>`) —
  meets DI-29. One entry per Markdown file in the directory with a
  frontmatter `id`, sorted by id, under `entries`, with a header saying the
  file is generated. A file with no `id` is skipped with a warning; no
  controlled document at all is an error.
- **Verification report** (`rdm/publishing/report.py`, `rdm story
  evidence-report --dhf … --allure-results … -o <pdf>`) — meets DI-64.
  `build_report()` takes each design input's status from the release
  context's verification data, the runs from the Allure reader, the risks
  and residual decisions from the risk register, and the record's commit
  from git. It names each reason the evidence is not release-grade (a design
  input not verified; a failed or broken run; a run with uncommitted changes
  or no commit; runs of another commit than the record's; no record commit)
  and lists the anomalies (no run, a run that did not pass, a missing
  attachment, an orphan tag). `render_pdf()` writes the data as JSON beside
  the layout and compiles it with the `typst` package (extra `report`) or a
  `typst` executable; with neither, the command fails and says so.
- **Report layout** (`rdm/publishing/verification_report.typ`) — meets DI-64.
  Reads `report.json` and sets every value as text, never markup, so nothing
  a test printed can change the document.
- **Evidence bundle** (`rdm/publishing/bundle.py`, `rdm story
  evidence-bundle`) — realises `release`'s DI-30: writes `verification.yml`,
  the rendered matrix (when the record has the template), `allure-results/`
  (every result and container and each attachment they name, plain files of
  the results directory only), the verification report PDF or the reason it
  was not rendered, and `manifest.json` listing the counts and files. It is
  here because it renders: the matrix with the Renderer, the report with the
  Verification report.
- **PDF action** (`action.yml`) — runs the project's `make pdfs` in the RDM
  image of the release it is pinned to and uploads the PDFs: the part of
  DI-63 (release) that renders the documents with the image of the same
  revision.

**Realised here:** the rendering side of **DI-1** (specification) — the
needs and design inputs read from frontmatter are what the documents
render — and of **DI-4** (release) — the traceability matrix is the
Renderer filling its template with `verification.yml`.

**Realised elsewhere:** DI-64's last clause by the release context — the
evidence bundle writes the report (or records in its manifest why not) and
the reusable gates workflow includes it through the bundle. The project
Makefile that runs Pandoc and Typst is a specification template
(`rdm/specification/init_files/`).

Verified by tagged tests in `tests/acceptance/`: DI-7, DI-8, DI-9 in
`test_rendering.py`; DI-16 in `test_ingestion.py`; DI-29 in
`test_record.py`; DI-64 in `test_verification_report.py`.

## Components (C3)

![Components: publishing](../../c4/views/C3_publishing.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Renderer | Templates and data to Markdown | Python, Jinja2 | `rdm/publishing/render.py` |
| Markdown extensions | Section numbers, vocabulary, audit notes | Python | `rdm/md_extensions/` |
| Code snippets | Collects tagged code snippets | Python | `rdm/publishing/collect.py` |
| DMR index | The device-master-record index from frontmatter | Python | `rdm/publishing/dmr.py` |
| Verification report | The PDF of every run behind each design input | Python | `rdm/publishing/report.py` |
| Report layout | The report's page layout | Typst | `rdm/publishing/verification_report.typ` |
| First-pass output | The words a first render produced, for the second pass to test against | Python | `rdm/publishing/first_pass_output.py` |
| Evidence bundle | The retained release evidence | Python | `rdm/publishing/bundle.py` |
| PDF action | Renders the documents in the image | GitHub Actions | `action.yml` |

The PDF action is in the Reusable gates container; the others are in `rdm`.

- The Verification report *builds on* Verification data (release), *reads
  results with* the Allure reader (test evidence), *reads risks with* the
  Risk register (risk), *reads git with* the shared kernel, and
  *lays out with* the Report layout.
- The DMR index *reads frontmatter with* the record reader
  (specification).
- The Renderer keeps the first pass's words in the First-pass output and
  post-processes the rendered Markdown with the Markdown extensions, which
  give the template the first pass's words; both use the shared kernel.
- The Evidence bundle writes `verification.yml` with the Verification data
  (release), the report PDF with the Verification report, and renders the
  matrix with the Renderer.
- The Evidence bundle (release) *writes* the Verification report and
  *renders the matrix with* the Renderer: arrows into publishing from below
  it (see Dependencies).

Open questions:

- The Renderer loads the extensions by name from configuration, so no
  relationship is drawn between them, and `rdm/publishing/first_pass_output.py`, which
  they share, is in no component.
- The PDF action's use of the Documents image is not drawn.
- A snippet key repeated across two files is not an error: the later file's
  snippet silently replaces the earlier one.
- The DMR index reads only the files directly in the given directory, not
  its subdirectories (such as `documents/design/`).

### Dynamic view

The two passes of a render: the Renderer loads the data files as the
template's context, keeps the words of the first pass, and post-processes the
Markdown with the extensions, which give the second pass the first pass's
words (so a glossary includes only the terms a document uses).

![Scenario: rendering a document](../../c4/views/D_publishing_render.svg)

## Dependencies

Layer 5 of the dependency rule, a read model: it depends on every context it
publishes from — `release` (the verification data), `test_evidence` (the
Allure reader), `specification` (the record reader), `risk` (the risk
register) — and the shared kernel. Nothing imports it but the composition
root. No import breaks the rule.
