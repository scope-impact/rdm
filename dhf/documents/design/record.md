---
id: SDS-REC-001
kind: design
context: record
satisfies: [UN-001, UN-004]
design_inputs:
  - id: DI-1
    text: "RDM shall read the user-need registry + satisfies refs from frontmatter and ingest Allure results, with no project-management dependency."
    traces_to: [UN-001, UN-004]
  - id: DI-6
    text: "RDM shall mark planning-tool outputs as non-record, keeping planning artifacts out of the controlled record."
    traces_to: []
---

# Record — Software Design

## Design Inputs

This context owns the design inputs declared in the frontmatter:

- **DI-1 (record ingest)** — read the user-need registry and per-context
  `satisfies` references from frontmatter, and ingest executed Allure results,
  without depending on any project-management tool. Refines UN-001 and UN-004.
- **DI-6 (plan/record separation)** — mark planning-tool outputs as non-record,
  keeping planning artifacts out of the controlled record. A cross-cutting
  constraint (`traces_to: []`). (That planning tooling is *optional* — the record
  core needs no planning extra — is the structural property DI-1's "no
  project-management dependency" test already pins.)

## Design Outputs

Ingests the system of record so the rest of RDM can compile and gate the DHF.

- `rdm/record/sdd.py` — discover per-context design documents (`kind: design`);
  read the user-need registry (`user_needs`), the design inputs (`design_inputs`),
  and `satisfies` references from frontmatter.
- `rdm/record/allure.py` — parse an Allure results directory into per-design-input
  executed status.
- `rdm/record/verify.py` — build the verification data the DHF renders from.

The layer is dependency-light (no pydantic / no planning extra), which is itself
how DI-6 is met. Acceptance criteria are verified by `@allure.story("DI-1")` and
`@allure.story("DI-6")` tests.
