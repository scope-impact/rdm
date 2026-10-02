---
id: SDS-RC-001
title: "Controlled records — Design"
kind: design
context: records
realises: [DI-3]
references: [SDS-SYS-001]
design_inputs:
  - id: DI-2
    text: "Every controlled document shall declare its identity and revision in frontmatter."
    traces_to: [UN-001]
  - id: DI-4
    text: "Each rendered controlled document shall embed its revision history from repository data, not a hand-maintained table."
    traces_to: [UN-001]
  - id: DI-5
    text: "The document control procedure shall address every item of the Part 11 document-control checklist, with the gap analysis reporting full coverage."
    traces_to: [UN-004]
  - id: DI-7
    text: "The device master record shall be a controlled index document enumerating the specification set, rendered from index data that is generated from the controlled documents' own frontmatter, so it lists each controlled document with its identity and revision."
    traces_to: [UN-005]
---

# Controlled records — Design

The controlled documents themselves: their identity, their generated
history, the procedure's Part 11 coverage, and the device master record that
indexes them. They live in `dhf/documents/procedures/` and render with
`make pdfs`.

## Design Inputs

- **DI-2 (document identity)** — id + revision in frontmatter makes the
  current approved revision identifiable. Refines UN-001.
- **DI-4 (generated history)** — the revision-history table in a rendered
  document comes from repository data (§11.10(e) altitude: the record is
  generated, not transcribed). Refines UN-001.
- **DI-5 (Part 11 coverage)** — the SOP must reference every checklist item;
  its test runs `rdm gap` and requires exit zero. Refines UN-004.
- **DI-7 (device master record)** — the DMR (§820.181 analog) is the current
  approved specification set: a controlled index document rendered from
  index data (`data/dmr.yml`) that `rdm story dmr` generates from the
  procedures' frontmatter, so the index cannot disagree with the documents
  it lists, listing each controlled document with its
  identity and revision. Being itself a controlled document, it flows through
  the same approval path it indexes. Refines UN-005.

It also **realises** DI-3, owned by `release`: the human-readable copies are
these documents rendered by the DHF's Makefile.

## Design Outputs

- `dhf/documents/procedures/document_control_procedure.md` — the SOP:
  frontmatter identity (DI-2), embedded history template (DI-4), Part 11
  references (DI-5).
- `dhf/documents/procedures/device_master_record_index.md` + `dhf/data/dmr.yml`
  — the DMR index and its generated data (DI-7).
- `dhf/data/history.yml` — the revision history the documents embed (DI-4).
- `checklists/part11_document_control.txt` — the audited Part 11 subset (DI-5).
- `dhf/Makefile`, `dhf/config.yml`, `dhf/template.typ` — rendering.

Each design input is one acceptance criterion, verified by a test tagged
`@allure.story("DI-n")` in `tests/acceptance/test_records.py`.
