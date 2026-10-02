# The gates

The gates decide pass or block from the record; they never change it. Each
runs the same way locally and in CI. The CI workflow `rdm adopt` installs runs
the design gate from day one; its acceptance-test, `verify` and release-gate
steps are commented out until your first design input and its test land, so
an empty record does not fail the release gate.

| Gate | Blocks | Runs |
| --- | --- | --- |
| **Design gate** — `rdm story design-gate` | implementation before the design is approved: design documents and design review present, complete, committed; every user-need and design-input id declared once | pre-commit hook; CI |
| **Release gate** — `rdm story release-gate` | a release unless every design input is verified by a passing tagged test, every user need is addressed, and every risk is evaluated, its risk controls verified and its residual acceptable | CI |
| **Gap analysis** — `rdm gap` | documents that do not reference every clause a checklist requires | on demand; add it to CI |
| **Graph validation** — `rdm graph validate` | the same rules, as SHACL shapes over the graph, plus warnings the coded gates do not give | on demand; add it to CI |

## Design gate

```bash
rdm story design-gate --dhf dhf
```

The design documents and the design review must be **present, complete** (no
`TODO` / `ENDTODO` markers) **and approved** (committed clean). Editing an
approved document reopens the gate. It also fails when a user-need or
design-input id is declared more than once, naming every document that
declares it, and warns about a user need no design input traces to, a
`traces_to` naming an unknown need, and tags that match no declared input.

The pre-commit hook (`rdm hooks .githooks && git config core.hooksPath
.githooks`, or set up by [`rdm adopt`](quickstart-existing-repo.md)) blocks
implementation commits while the gate is red. Committing only design documents
is always allowed — that commit is the approval of the design.

## Release gate

```bash
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

Passes only when the design is approved **and** every design input is verified
by a passing tagged test **and** every user need is addressed by at least one
design input **and**, when the DHF has a risk register, every risk is evaluated
against the declared risk policy, controlled by verified design inputs, and
residually acceptable or accepted ([risk register](risk.md)).

It names every user need without an approved human validation record, as a
warning: a machine cannot supply that judgment, but its absence is never
silent.

## Gap analysis

```bash
rdm gap 62304_2015_class_b dhf/documents/*.md
```

Reports the checklist clauses no document references with a `[[KEY]]` tag —
[gap analysis](gap-analysis.md), [checklist format](checklist-format.md).

## Graph validation

```bash
rdm graph validate --allure-results dhf/allure-results --checklist part11_document_control
```

The gate rules as SHACL shapes, held by an acceptance test to blocking exactly
what the release gate and `rdm gap` block, plus warnings only a graph can give
— stale results, a test that never ran, a document not yet landed. The rules:
[gate rules as SHACL](graph-shapes.md).

## Independent review

A passing test proves code ran, not that the requirement is met. That
judgment belongs to the pull-request reviewer, who must not be the change's
author: the repository's rules require an approving review, and CI runs the
gates on every change. The reviewer reads each affected design input against
its tagged test and asks whether the test would fail if the behaviour broke —
a tautology, a mocked-out code path, or two of three clauses covered is a
reason to request changes.

To back that judgment with an executed check, break the clause on purpose and
see whether the test notices:

```bash
rdm story mutation-probe --file <impl> --find '<code for a clause>' \
  --replace '<one-line break>' --test <test_name>   # KILLED = the test catches it
```

The probe runs the test once unmutated first and refuses one that does not
pass — a red test would "catch" anything. It always restores the file, even if
interrupted, and counts only a genuine test failure as a catch. It records
nothing and never gates a release. Teams that want automated evidence of test
strength can run a mutation-testing tool (for example
[mutmut](https://mutmut.readthedocs.io/)) in CI; its score is not DHF
evidence.

## Release evidence

```bash
rdm story evidence-bundle --dhf dhf --allure-results dhf/allure-results -o release-evidence/
rdm story evidence-report --dhf dhf --allure-results dhf/allure-results -o verification_report.pdf
rdm story dmr documents/ -o data/dmr.yml
```

The evidence bundle is the retained release evidence: verification data, the
rendered traceability matrix, the executed Allure results with every
attachment and container they reference, the verification report, and a
manifest — ready to attach to
a release tag, so the evidence outlives CI artifact retention. Uploaded as a
CI artifact, the pipeline records its digest. `dmr` generates the
device-master-record index (id, title, path, revision per controlled
document) from the documents' own frontmatter.

### The verification report

The matrix says *that* a design input is verified. The verification report
(`verification_report.pdf`, DI-64) shows *what was run* to say so, for the
person who judges the evidence without having run it: an auditor, a notified
body, the pull-request reviewer. It follows their questions, in order:

1. **What is this, and can I rely on it.** The repository, the record's
   commit, the commits tested, who or what ran the tests and where (the CI run
   with its link, or a local user and host; the OS and tool versions — written
   by `rdm.pytest_plugin`, DI-65), the RDM version, and one SHA-256 over the
   result files. Then the **evidence status**: *release-grade* only when every
   design input has a passing run, none failed, and every run tested the
   record's commit with no uncommitted changes. Otherwise it says *not
   release-grade* and names each reason.
2. **What went wrong.** Failed, broken and skipped runs, design inputs with no
   run, missing attachments, and tags that name no design input.
   Beside it, the **risk register**: how many ratings are still proposals no
   person approved, and how many residuals are not evaluated.
3. **What traces to what.** One table: design input, user needs, the risks it
   is a control for (from the risk register), its tests, the result, and the
   page.
4. **The evidence.** Per design input — its text; whether it is a *baseline*
   or a *risk-based* acceptance criterion; its bounded context, user needs and
   outputs; each risk it is a control for, with that risk's status and residual
   decision — each run: the test's file and function, result, date and
   duration, failure message and trace, its links, and its numbered
   **verification steps** with their own results. Under each, the attachments
   the test made: text inline (the first 60 lines), images embedded, other
   files by SHA-256. A run's commit and worktree appear only where they differ
   from the header.
5. **How to check it.** An appendix of every result file and its SHA-256, and
   how the header's SHA-256 is computed from them, so the report can be held
   against the retained evidence bundle.

It speaks the [glossary](glossary.md)'s language: a design input *is* the
acceptance criterion and the test verifies it; a design input is a control
*for* a risk and the risk is never shown as controlled until its residual is
evaluated acceptable; a residual on a proposed rating says so.

Left out, because it repeats the report or buries it: labels shown elsewhere
(story, epic, feature, output, commit, worktree), Allure's severity label
(which reads as a harm's severity and is not one), runner internals (host,
thread, framework, language, suite, package), and pytest's captured stdout and
stderr and the plugin's copy of the requirement, which are listed by
checksum. All of it stays in the results and the bundle.

Text from a test (an attachment, a failure message) reaches the page as text:
the data is handed to the Typst layout as JSON, never spliced into markup, so
it cannot change the document.

It needs Typst: install `rdm[report]` (the `typst` package, compiled
in-process), or have a `typst` executable on `PATH`, as the RDM image does.
Without either, the bundle's manifest says why it has no report. The
[reusable gates](reusable-ci.md) install the extra, so every CI run uploads
the report inside `rdm-evidence`.
