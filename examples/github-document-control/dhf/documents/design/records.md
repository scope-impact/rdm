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

# Controlled records — Software Design

## Purpose

The controlled documents themselves: their identity and revision, their
revision history generated from data, the procedure's coverage of the Part 11
document controls, and the device master record that indexes the current
approved set.

## Design Outputs

The design outputs are this context's architecture, in the C4 model of the
architecture workspace (`rdm c4 draw`): its components, what each is
responsible for, and how they relate. The workspace maps each component to
what implements it.

![Components: records](../../c4/views/C3_records.svg)

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Document control procedure | SOP-DC-001: identity, revision history, the Part 11 controls it cites | DI-2, DI-4, DI-5 |
| Device master record index | The current approved specification set, from generated index data | DI-2, DI-7 |
| Record data | The revision history and the index data the documents embed | DI-4, DI-7 |
| Part 11 checklist | The Part 11 controls a controlled document must reference | DI-5 |
| Rendering | Renders the documents from their templates and data to PDF | DI-4 |

The author writes the procedure, which embeds its revision history from the
record data; the device master record index lists the documents from the
index data, generated from their own frontmatter. Rendering renders both with
RDM, and the release workflow renders the copies with it: this context's
part of `release`'s DI-3. The approval path holds the procedure to the Part
11 checklist (`approval` realises DI-5).

## Dependencies

Depends on RDM (rendering, gap analysis, the index data). `approval` and
`release` depend on it: the first holds its documents to the checklist, the
second releases them.
