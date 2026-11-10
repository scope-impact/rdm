# RDM — Design History File

This is RDM's own DHF: RDM's own record, checked by RDM's own gates. Every
change lands through a reviewed pull request with the gates green. The method
is the docs' *How RDM works* (`docs/about/how-rdm-works.md`); the direction,
the spec moving to the factory (Löyly), is ADR 0002 (`decisions/`).

Scope note: RDM is not a medical device, so this DHF deliberately implements
the **design-controls slice** (requirements, verification, validation,
traceability — the §820.30-shaped record) plus a register of RDM's own tool
risks, and not the full IEC 62304 lifecycle document set (a complete risk
management file, SOUP register, maintenance and problem-resolution plans, …).
Running `rdm gap 62304_2015_class_b` over these documents is expected to
report those items as missing.

## Layout (the record)

```
documents/
  verification_and_validation_plan.md the V&V approach
  document_control.md                git/GitHub as this record's document control (Part 11-mapped)
  architecture.md                    system design / bounded contexts (design only, no needs)
  traceability_matrix.md             matrix TEMPLATE (rendered from generated data; never hand-edited)
  design/                            one `kind: design` doc per bounded context:
    <context>.md                       the design output (how) of one context
  risk/
    policy.md                          risk acceptability policy (RMP-001, proposed)
    tool_risks.md                      `kind: risk` register of RDM's tool risks (RMF-001, proposed)
decisions/                           ADR 0002: the spec lives with the factory; RDM audits it
```

RDM's requirements and user needs are its spec, outside this directory, in
Löyly's layout: `.pi/plan/NN-<context>` → `.pi/spec/<context>/` with a
`spec.feature` of stamped scenarios (`item-NN-SSS`) and the Allium surfaces
of its user needs. Trace any of them through the graph:

```bash
rdm graph query --dhf dhf '…'           # SPARQL; or the `trace` tool of `rdm graph mcp`
```

The gates, as CI runs them, are in `CLAUDE.md`.
`dhf/allure-results/` and `dhf/data/verification.yml` are generated
(gitignored) by running the acceptance suite, never committed.

## `docs/` and `dhf/`

`docs/` explains RDM to its users; `dhf/` is the controlled record of how RDM
itself is designed and verified. One explains, the other decides.

| | `docs/` | `dhf/` |
|---|---|---|
| **What it is** | RDM's user documentation, plus an API reference generated from the docstrings | RDM's own design history file: the regulated record RDM develops itself under |
| **Who it's for** | People and agents using RDM on their own product | Whoever governs RDM's development: authors, reviewers, an auditor, and RDM's gates |
| **Controlled?** | No: ordinary docs, changed by a normal reviewed pull request | Yes: every change is a reviewed pull request; the release gate requires every requirement in it verified |
| **Checked by** | `mkdocs build --strict` (links, navigation) | The release gate, `rdm graph validate`, and the tests tagged with its ids |
| **Read by RDM's tools?** | No | Yes: the gates, `trace`, the graph, the verification report and the evidence bundle |
| **Published** | As the docs site | On the same site, each document as a page and a PDF, with the traceability matrix and map generated from a live test run at each build |

Nothing flows from `docs/` into the record. Where the docs would restate what
the record decides, they link to it instead. A product that adopts RDM gets
its own `dhf/` (`rdm adopt` or `rdm init`), not RDM's `docs/`.

## Agents in this repository

Agents working on RDM read the record through `rdm graph mcp`, registered in
the repository's `.mcp.json`. A session's bootstrap script syncs
dependencies; CI runs the same gates for an agent's change as for a
person's.
