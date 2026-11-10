# The gates

The gates decide pass or block from the record; they never change it. Each
runs the same way locally and in CI. In the CI workflow `rdm adopt` installs,
the acceptance tests and release gate are inputs set to `false` until your
first requirement's test lands ([phased adoption](ci.md)).

| Gate | Blocks | Runs |
| --- | --- | --- |
| **Release gate** — `rdm story release-gate` | a release unless the spec is read whole, every requirement is verified by a passing tagged run, every user need is addressed, and every risk is evaluated, its risk controls verified and its residual acceptable | CI |
| **Gap analysis** — `rdm gap` | documents that do not reference every clause a checklist requires | on demand; add it to CI |
| **Graph validation** — `rdm graph validate` | the same rules, as SHACL shapes over the graph (what the release gate blocks about the whole record, such as an unreadable document or result, is on the record's node), plus warnings the coded gates do not give | on demand; add it to CI |

The rules each gate applies, and what it reports, are its design:
[release gate](../dhf/documents/design/release.md),
[risk rules](../dhf/documents/design/risk.md),
[gap analysis](../dhf/documents/design/compliance.md),
[graph shapes](../dhf/documents/design/graph.md).

## Release gate

```bash
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

It blocks, each one named, on:

| Event | When |
| --- | --- |
| *No Spec* | no `.pi/plan` was found up to the repository root, and `dhf/config.yml` names no `spec:` |
| *Unstamped Scenario* | a scenario of an accepted slice carries no `@item-NN-SSS` |
| *Item Stamped Twice* | one item id is on more than one scenario |
| *Unreadable Spec* | an event-model or event-storm file cannot be read |
| *No Requirements* | the spec holds no stamped scenario: nothing to verify |
| *Requirement Failed* / *Requirement Untested* | a run claiming the requirement failed or broke; or no run claiming it passed |
| *Unreadable Result* | a result file it cannot read: it could hold a failed run |
| *Need Unaddressed* | a user need no requirement addresses by covering one of its rules or guarantees |
| *Risk Finding* | a blocking finding of the [risk rules](risk-register.md) |

It warns, never blocks, on a user need without an approved human validation
record (a machine cannot supply that judgment, but its absence is never
silent), on a proposed risk, and on an item tag that names no requirement.

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
| a user need no requirement addresses by covering its rules or guarantees | an unaddressed need |
| a requirement with no passing test run, or with a failed or broken one | unverified |
| a checklist clause no document references | a gap against the standard |
| a clause without a key or a standard; a checklist member that is not a clause | malformed checklist data |
| an item id stamped on more than one scenario | stamped once |
| a risk with no id, hazard, situation, harm or category; a security risk with no STRIDE category; a risk id declared twice | an incomplete register |
| a risk not evaluated against a risk policy; a recorded level the policy contradicts; controls with no residual score; a control or link naming nothing declared; an unknown status; a residual that does not allow release | the risk rules ([risk register](risk-register.md)) |
| a document referencing a document the record does not hold | a dangling reference |

**Warnings**, reported, never blocking:

| Rule | Why |
| --- | --- |
| a requirement that addresses no user need | the spec does not say whom it serves |
| a test run claiming an item id that is no requirement of the spec | a claim that resolves to nothing |
| a test run tied to no commit | the version it is evidence for is unknown |
| a test run of another commit than the record's | stale evidence |
| a document whose latest change has not landed on the default branch | not yet merged |
| a risk with `status: proposed` | no person has approved its rating |

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
author ([the review step](changing-the-record.md)). The reviewer reads each
changed test against its scenario's text and asks whether the test would
fail if the code did not do what the scenario says.

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

The **verification report** shows what was run to verify each requirement,
for someone who did not run it: an auditor, a notified body, the reviewer. It
says whether the evidence is release-grade and why not, and ends with every
result file's SHA-256 so it can be checked against the bundle. What it holds
is item-07-009 in the [publishing design](../dhf/documents/design/publishing.md). It
needs Typst: install `rdm[report]`, or have `typst` on `PATH` as the RDM
image does. The [reusable gates](ci.md) upload it in `rdm-evidence`.

`dmr` writes the device-master-record index (id, title, path and revision of
each controlled document) from the documents' own frontmatter.

The user manual belongs in that index too: a controlled document of
`kind: manual` lists its pages under `pages:` (paths from the project root),
and the pages stay where the site builds them. RDM's own manual is
`dhf/documents/user_manual.md`.
