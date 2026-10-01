# Gate rules as SHACL

`rdm graph validate` checks the graph against the gate rules, written as SHACL
shapes in `rdm/graph/shapes.ttl`:

```bash
rdm graph validate --allure-results dhf/allure-results --checklist part11_document_control
```

**Violations** — the release-blocking rules, the same ones the coded gates
enforce:

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

**Warnings** — reported, never blocking:

| Rule | Why |
| --- | --- |
| a design input with no tagged test | nothing claims to verify it yet |
| a `tracesTo` or `realises` naming an undeclared need or input; a test tag sharing the design-input prefix but naming no declared input | a reference that resolves to nothing |
| a test run tied to no commit | the version it is evidence for is unknown |
| a test run of another commit than the record's | stale evidence |
| a tagged test with no run, while other tests in its file ran | a claim never executed |
| a test run exercising a design input its test does not claim | the source and the results disagree |
| a document whose latest change has not landed on the default branch | not yet merged |
| a risk with `status: proposed` | no person has approved its rating |
| a bounded context with a design document but missing from the architecture's `contexts:` | the architecture fell behind |

It exits 1 on any violation. The coded gates (`rdm story release-gate`,
`rdm gap`) remain authoritative; an acceptance test holds the shapes to
blocking exactly the same design inputs and user needs.

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
