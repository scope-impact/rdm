# How RDM controls itself

RDM is developed under RDM's own design controls. RDM is the product; its
design history file is [`dhf/`](https://github.com/scope-impact/rdm/tree/main/dhf)
in this repository, and every change to RDM goes through the same loop RDM
asks of a device team: record first, then a tagged test, then the gates, then
a reviewed pull request.

## What is in RDM's record

```
dhf/
  AGENT_WORKFLOW.md                       the change procedure (start here)
  documents/
    verification_and_validation_plan.md   user needs (UN-…) and the V&V approach
    architecture.md                       the bounded contexts and the part each belongs to
    design/<context>.md                   one per context: the design inputs it owns, and its design
    risk/policy.md, risk/tool_risks.md    risk policy and register of RDM's own tool risks
    design_review.md                      the design review log — one section per review
    document_control.md                   git and GitHub as the record's document control (Part 11)
    traceability_matrix.md                matrix template — rendered, never hand-edited
tests/acceptance/                         one or more tagged tests per design input
```

RDM is not a medical device, so its record implements the design-controls
slice — design inputs, review, verification, validation, traceability, tool
risks — not the full IEC 62304 document set. `rdm gap 62304_2015_class_b` over
these documents reports those other items as missing, as it should.

The live inventory is in the record, not on this page:

```bash
rdm story new-input --dhf dhf --list     # contexts, design inputs, user needs
rdm story trace DI-40                    # one design input's slice
```

## How a change runs

1. **Record first.** A new behaviour gets a design input
   (`rdm story new-input`) and a section in the design review; that commit is
   made before any code, and the pre-commit hook blocks implementation
   commits until it exists.
2. **The test.** The scaffolded stub fails until it is replaced by real
   assertions, in named verification steps.
3. **A reviewer's check.** Each behaviour's test is probed with
   `rdm story mutation-probe`: break one line, expect the test to fail.
4. **The gates, as CI runs them**, on every push:

    ```bash
    rdm story design-gate --dhf dhf
    pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
    rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results
    ```

5. **The pull request**, reviewed by someone other than its author, merged
   with a merge commit so the history still shows the design was committed
   before the code.

The full procedure, with the decision of whether a change needs a design
input, is [`dhf/AGENT_WORKFLOW.md`](https://github.com/scope-impact/rdm/blob/main/dhf/AGENT_WORKFLOW.md)
([summary](agent-workflow.md)).

## The evidence on this site

- [Traceability matrix](traceability-matrix.md) — generated on every docs
  build from a live run of the acceptance suite.
- [Document control](document-control.md) — how git and GitHub meet the
  Part 11 controls for RDM's record, checked by `rdm gap`.

## Agents in this repository

Agents working on RDM read the record through `rdm graph mcp`, registered in
the repository's `.mcp.json` ([for agents](agents.md)). A session's bootstrap
script syncs dependencies and turns on the design-gate hook, so an agent works
under the same gates as a person.
