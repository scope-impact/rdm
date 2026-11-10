# RDM — Design History File

This is RDM's own DHF: the record RDM keeps about itself, checked by RDM's own
gates. Every change lands through a pull request that someone other than its
author approves, with the gates green. How RDM works is in the docs
(`docs/about/how-rdm-works.md`); why RDM reads a spec as Gherkin rather than
authoring one is ADR 0002 (`decisions/`).

RDM is not a medical device, so this DHF holds the **design-controls slice**
(requirements, verification, validation, traceability, tool risks), not the
full IEC 62304 lifecycle set (a complete risk management file, SOUP register,
maintenance and problem-resolution plans, …). `rdm gap 62304_2015_class_b`
over these documents reports those items as missing, as expected.

## What the record is

| Part | Where | What it holds |
|---|---|---|
| **The spec** | `spec/<context>/<need>.feature` (outside `dhf/`) | RDM's requirements and user needs, in Gherkin: each Feature a user need, each scenario tagged `@item-NN-SSS` a requirement |
| **The tests** | `tests/acceptance/` | each test claims the requirement it verifies with `@allure.tag("item-NN-SSS")` |
| **This directory** | `dhf/` | the documents below, and generated evidence |

```
config.yml                           render configuration
documents/
  verification_and_validation_plan.md how RDM is verified and validated
  architecture.md                    the parts, the bounded contexts and the dependency rule
  design/<context>.md                the design output of one bounded context
  risk/policy.md                     risk acceptability policy (RMP-001, proposed)
  risk/tool_risks.md                 register of RDM's tool risks (RMF-001, proposed)
  document_control.md                git and GitHub as this record's document control (DC-001, Part 11-mapped)
  user_manual.md                     the IFU: the docs site's pages, in reading order (IFU-001)
  traceability_matrix.md             the matrix template, rendered from generated data, never hand-edited
decisions/
  adr-0002-…md                       RDM reads a spec as Gherkin and audits it
```

Generated, never committed (gitignored): `dhf/allure-results/` (the
acceptance run) and `dhf/data/` (the verification data the matrix renders
from).

Run the gates as CI does (`CLAUDE.md`):

```bash
uv run pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
uv run rdm graph validate --dhf dhf --allure-results dhf/allure-results
```

Trace a requirement, user need or risk with the `trace` tool of
`rdm graph mcp` (registered in `.mcp.json`), or query the record with
`rdm graph query`.

## `docs/` and `dhf/`

`docs/` explains RDM to its users; `dhf/` is the record of how RDM itself is
designed and verified.

| | `docs/` | `dhf/` |
|---|---|---|
| **What it is** | RDM's instructions for use, plus an API reference generated from the docstrings | RDM's own design history file |
| **Who it's for** | People and agents using RDM on their own product | Whoever governs RDM's development: authors, reviewers, an auditor, and RDM's gates |
| **Controlled?** | Yes, as one document: IFU-001 lists its pages; each change is a reviewed pull request | Yes: each change is a reviewed pull request; the release gate requires every requirement verified |
| **Checked by** | `mkdocs build --strict`, and the tests of the IFU's own requirements | The release gate, `rdm graph validate`, `rdm gap` |
| **Published** | As the docs site, one copy per release | On the same site, each document as a page (and a PDF where Pandoc and Typst run), with the traceability matrix and map generated from a live test run |

A product that adopts RDM gets its own `dhf/` (`rdm adopt` or `rdm init`), not
RDM's.
