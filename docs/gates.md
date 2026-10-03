# The gates

The gates decide pass or block from the record; they never change it. Each
runs the same way locally and in CI. The CI workflow `rdm adopt` installs runs
the design gate from day one; its acceptance tests and release gate are
inputs set to `false` until your first design input and its test land
([phased adoption](reusable-ci.md)).

| Gate | Blocks | Runs |
| --- | --- | --- |
| **Design gate** — `rdm story design-gate` | implementation before the design is approved: design documents and design review present, complete, committed; every user-need and design-input id declared once | pre-commit hook; CI |
| **Release gate** — `rdm story release-gate` | a release unless every design input is verified by a passing tagged test, every user need is addressed, and every risk is evaluated, its risk controls verified and its residual acceptable | CI |
| **Gap analysis** — `rdm gap` | documents that do not reference every clause a checklist requires | on demand; add it to CI |
| **Graph validation** — `rdm graph validate` | the same rules, as SHACL shapes over the graph (what the release gate blocks about the whole record, such as an unreadable document or result, is on the record's node), plus warnings the coded gates do not give | on demand; add it to CI |

The rules each gate applies, and what it reports, are its design:
[design gate](dhf/documents/design/specification.md),
[release gate](dhf/documents/design/release.md),
[risk rules](dhf/documents/design/risk.md),
[gap analysis](dhf/documents/design/compliance.md),
[graph shapes](dhf/documents/design/graph.md).

## Design gate

```bash
rdm story design-gate --dhf dhf
```

The pre-commit hook (`rdm hooks .githooks && git config core.hooksPath
.githooks`, or set up by [`rdm adopt`](get-started.md)) blocks
implementation commits while the gate is red. Committing only design documents
is always allowed: that commit is the approval of the design.

## Release gate

```bash
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

A user need without an approved human validation record is named as a
warning: a machine cannot supply that judgment, but its absence is never
silent.

## Gap analysis

```bash
rdm gap 62304_2015_class_b dhf/documents/*.md
```

See [gap analysis](gap-analysis.md).

## Graph validation

```bash
rdm graph validate --allure-results dhf/allure-results --checklist part11_document_control
```

The gate rules as SHACL shapes (`rdm/graph/shapes.ttl`). An acceptance test
holds them to blocking exactly what the release gate and `rdm gap` block; the
coded gates stay authoritative. It exits 1 on any violation.

**Violations**, which block:

| Rule | Why |
| --- | --- |
| a user need no design input traces to | an unaddressed need |
| a design input with no passing test run, or with a failed or broken one | unverified |
| a checklist clause no document references | a gap against the standard |
| a clause without a key or a standard; a checklist member that is not a clause | malformed checklist data |
| a user-need or design-input id declared more than once | defined once |
| a risk with no id, hazard, situation, harm or category; a security risk with no STRIDE category; a risk id declared twice | an incomplete register |
| a risk not evaluated against a risk policy; a recorded level the policy contradicts; controls with no residual score; a control or link naming nothing declared; an unknown status; a residual that does not allow release | the risk rules ([risk register](risk.md)) |
| a document referencing a document the record does not hold | a dangling reference |

**Warnings**, reported, never blocking:

| Rule | Why |
| --- | --- |
| a design input with no tagged test | nothing claims to verify it yet |
| a `tracesTo` or `realises` naming an undeclared need or input; a test tag sharing the design-input prefix (its leading letters, in any case) but naming no declared input | a reference that resolves to nothing |
| a test run tied to no commit | the version it is evidence for is unknown |
| a test run of another commit than the record's | stale evidence |
| a tagged test with no run, while other tests in its file ran | a claim never executed |
| a test run exercising a design input its test does not claim | the source and the results disagree |
| a document whose latest change has not landed on the default branch | not yet merged |
| a risk with `status: proposed` | no person has approved its rating |
| a bounded context with a design document but missing from the architecture's `contexts:` | the architecture fell behind |
| the C4 model disagreeing with the record (DI-68) | the architecture fell behind |

Add your own rules as more shape files, with no code change:

```bash
rdm graph validate --shapes team-rules.ttl
```

```turtle
@prefix sh:  <http://www.w3.org/ns/shacl#> .
@prefix rdm: <https://github.com/scope-impact/rdm/ns#> .
@prefix dcterms: <http://purl.org/dc/terms/> .

[] a sh:NodeShape ;
   sh:targetClass rdm:Document ;
   sh:property [ sh:path dcterms:title ; sh:minCount 1 ;
                 sh:message "every controlled document needs a title" ] .
```

## Independent review

A passing test proves code ran, not that the requirement is met. That
judgment belongs to the pull-request reviewer, who must not be the change's
author ([the review step](agent-workflow.md)). To back it with an executed
check, break what the design input requires on purpose and see whether its
test notices:

```bash
rdm story mutation-probe --file <impl> --find '<code it requires>' \
  --replace '<one-line break>' --test <test_name>   # KILLED = the test catches it
```

The probe refuses a test that fails before the mutation, always restores the
file, records nothing and never gates a release.

## Release evidence

```bash
rdm story evidence-bundle --dhf dhf --allure-results dhf/allure-results -o release-evidence/
rdm story evidence-report --dhf dhf --allure-results dhf/allure-results -o verification_report.pdf
rdm story dmr documents/ -o data/dmr.yml
```

The **evidence bundle** is the retained release evidence: the verification
data, the rendered traceability matrix, the executed Allure results with
every attachment, the verification report and a manifest, ready to attach to
a release tag so the evidence outlives CI retention.

The **verification report** shows what was run to verify each design input,
for someone who did not run it: an auditor, a notified body, the reviewer. It
says whether the evidence is release-grade and why not, and ends with every
result file's SHA-256 so it can be checked against the bundle. What it holds
is DI-64 in the [publishing design](dhf/documents/design/publishing.md). It
needs Typst: install `rdm[report]`, or have `typst` on `PATH` as the RDM
image does. The [reusable gates](reusable-ci.md) upload it in `rdm-evidence`.

`dmr` writes the device-master-record index (id, title, path and revision of
each controlled document) from the documents' own frontmatter.
