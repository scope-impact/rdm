# How RDM controls itself

RDM is developed under RDM's own design controls. RDM is the product; its
design history file is [`dhf/`](dhf/README.md)
in this repository, and every change to RDM goes through the same loop RDM
asks of a device team: record first, then a tagged test, then the gates, then
a reviewed pull request.

## `docs/` and `dhf/`

`docs/` explains RDM to its users; `dhf/` is the controlled record of how RDM
itself is designed and verified. One explains, the other decides.

| | `docs/` | `dhf/` |
|---|---|---|
| **What it is** | RDM's user documentation: this site, plus an API reference generated from the docstrings | RDM's own design history file: the regulated record RDM develops itself under |
| **Who it's for** | People and agents using RDM on their own product | Whoever governs RDM's development: authors, reviewers, an auditor, and RDM's gates |
| **What's in it** | How RDM works, the data model, the gates, the graph, agents, how-tos, the CLI reference, research notes, the glossary (included from `CONTEXT.md`) | The V&V plan with the user needs (UN-nnn); one design document per bounded context with its design inputs (DI-n) and C3 view; the architecture (C1/C2); the risk policy and register; the design reviews; document control; the agent workflow; generated data (Allure results, `verification.yml`) |
| **Controlled?** | No: ordinary docs, changed by a normal reviewed pull request | Yes: the design gate requires it complete and committed before code; the release gate requires every design input in it verified |
| **Checked by** | `mkdocs build --strict` (links, navigation) | The design gate, the release gate, `rdm graph validate`, and the tests tagged with its ids |
| **Read by RDM's tools?** | No | Yes: the gates, `trace`, the graph, the verification report and the evidence bundle |
| **Published** | As this site | Not as pages; this site shows one generated view of it, the traceability matrix, from a live test run at each build |

Nothing flows from `docs/` into the record. Where a docs page restates what
the record decides, the record wins: docs link to the record rather than
repeat it. A product that adopts RDM gets its own `dhf/` (`rdm adopt` or
`rdm init`), not RDM's `docs/`.

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
input, is [`dhf/AGENT_WORKFLOW.md`](dhf/AGENT_WORKFLOW.md)
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
