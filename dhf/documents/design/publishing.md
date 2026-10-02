---
id: SDS-REN-001
kind: design
context: publishing
# Implements part of inputs other contexts own.
realises: [DI-1, DI-4]
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

> **Interim (Design Review 30).** This context was formed from `rendering`, `ingestion`, `record`, `verification`. Its
> design inputs moved here unchanged; the prose below is carried over verbatim
> from those documents, by section, until it is rewritten for this context.

## Design Inputs

Renders the design record into controlled documents: templates filled with data generated from the record, then Markdown, PDF and DOCX.

- **DI-7 (template rendering)** — render a Markdown template against a supplied
  data context with Jinja2, so a generated data file (e.g. `verification.yml`)
  populates the document.
- **DI-8 (traceability filters)** — provide `invert_dependencies`, `join_to`,
  and `md_indent`, the filters that build traceability tables.
- **DI-9 (markdown post-processing)** — auto-number sections, expand declared
  vocabulary/acronyms, and exclude auditor-only `[[…]]` notes from released
  output.
- **DI-16 (code-snippet collection)** — extract `RDOC … ENDRDOC` delimited
  snippets from source files, keyed by name, so live code is embedded in docs.
- **DI-29 (DMR index data)** — `rdm story dmr` generates device-master-record
  index data from the controlled documents' own frontmatter (one entry per
  document: id, title, path, revision), so the DMR index is derived from the
  record rather than hand-maintained — the same generated-not-transcribed rule
  the traceability matrix follows. Refines UN-012.
- **DI-64 (verification report, PDF)** — the matrix says *that* a design
  input is verified; the report shows *what was run* to say so, written for
  the person who has to judge it without having run anything: an auditor, a
  notified body, the pull-request reviewer. It speaks the record's language
  (`CONTEXT.md`): a design input is an acceptance criterion, baseline or
  risk-based; a test verifies it through its verification steps; a design input
  allocated to a risk is a *control for* it, never shown as making the risk
  controlled until the risk's residual is evaluated acceptable. `rdm story evidence-report`
  renders it from the record and the Allure results it is given. Its order is
  the order of their questions:
  - **what is this, and can I rely on it** — the repository, the record's
    commit, the commits tested, who or what ran the tests and where (DI-65),
    the RDM version, one SHA-256 over the result files; and an *evidence
    status*: release-grade only when every design input has a passing run,
    none failed, and every run tested the record's commit with a clean
    worktree. Otherwise it is not, and each reason is named. Beside it, the
    state of the risk register: how many ratings are still proposals, and how
    many risks have no evaluated residual;
  - **what went wrong** — failed, broken or skipped runs, and design inputs
    with no run, up front;
  - **what traces to what** — one table: user need, design input, the risks
    it is a control for (from the risk register), its tests, their result;
  - **the evidence** — per design input, then per run: the test's file and
    function, the result, date and duration, each step as a verification
    step with its own result, failure message and trace, and the
    attachments the test made (text inline up to a limit, images embedded,
    other files by SHA-256). The commit and worktree appear on a run only
    where they differ from the header;
  - **how to check it** — an appendix of every result file and its SHA-256,
    against the retained bundle.
  What would only repeat the report or bury it is left out: labels the report
  already shows (story, epic, feature, output, commit, worktree) and runner
  internals (host, thread, framework, language, suite, package), and the
  Allure severity label, which reads as a harm's severity and is not one; pytest's
  captured stdout and stderr, and the plugin's copy of the requirement, are
  listed by name and SHA-256, not printed. The data reaches the Typst layout
  as a JSON file, never spliced into markup, so no text from a test can change
  the document. It compiles with the `typst` package (extra `report`) or a
  `typst` executable (the RDM image has one). The evidence bundle and the
  reusable gates workflow include it. Refines UN-012.

The rest of `ingestion`'s design is carried over in [test_evidence](test_evidence.md).

The rest of `record`'s design is carried over in [specification](specification.md).

The rest of `verification`'s design is carried over in [release](release.md).

## Design Inputs (context notes) — from `rendering`

This context owns the rendering requirements (the engine that compiles the DHF
from data + templates), all refining UN-001:

It also **realises** the rendering side of inputs owned elsewhere (via
`realises`): **DI-1** (record ingest, owned by `record`) and **DI-4**
(traceability, owned by `verification`).

## Design Outputs — from `rendering`

Compiles the DHF from data and templates.

- `rdm/render.py` — two-pass Jinja2 engine; the `invert_dependencies`, `join_to`,
  `md_indent` filters; context keyed by data-file basename, so a generated
  `verification.yml` renders into a traceability matrix.
- `rdm/md_extensions/` — section numbering, vocabulary expansion, auditor-note
  exclusion.
- `rdm/c4.py` — `rdm c4 draw`: the workspace's model and its views, drawn
  and stamped (DI-70).
- Output: Markdown → PDF/DOCX via Pandoc/Typst.

Contributes to **UN-001** (compile the DHF from the system of record). The owned
inputs are verified by `@allure.story("DI-7" / "DI-8" / "DI-9" / "DI-70")` acceptance tests;
the realised inputs by their owners' tests plus RDM's existing render unit tests.

## Components (C3)

The components of the `publishing` context, drawn from the architecture
workspace (`rdm c4 draw`).

![Components: publishing](../../c4/views/C3_publishing.svg)
