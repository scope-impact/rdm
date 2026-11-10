---
id: SDS-RC-001
title: "Controlled records — Design"
context: records
references: [SDS-SYS-001]
---

# Controlled records — Software Design

## Purpose

The controlled documents themselves: their identity and revision, their
revision history generated from data, the procedure's coverage of the Part 11
document controls, and the device master record that indexes the current
approved set.

## Design Outputs

The design outputs are this context's components: what each is responsible
for, and which requirements of the spec it meets.

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Document control procedure | SOP-DC-001: identity, revision history, the Part 11 controls it cites | item-02-001, item-02-002, item-02-003 |
| Device master record index | The current approved specification set, from generated index data | item-02-001, item-02-004 |
| Record data | The revision history and the index data the documents embed | item-02-002, item-02-004 |
| Part 11 checklist | The Part 11 controls a controlled document must reference | item-02-003 |
| Rendering | Renders the documents from their templates and data to PDF | item-02-002 |

The author writes the procedure, which embeds its revision history from the
record data; the device master record index lists the documents from the
index data, generated from their own frontmatter. Rendering renders both with
RDM, and the release workflow renders the copies with it: this context's
part of `release`'s item-03-001. The approval path holds the procedure to the Part
11 checklist (`approval` implements part of item-02-003).

## Dependencies

Depends on RDM (rendering, gap analysis, the index data). `approval` and
`release` depend on it: the first holds its documents to the checklist, the
second releases them.
