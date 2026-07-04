# Gap analysis — audit documents against a standard

`rdm gap` checks that your documents contain the references a chosen
standard's checklist requires. A reference is a `[[KEY]]` marker in the
document; the checklist maps keys to the standard's clauses.

```bash
rdm gap --list                                     # shipped checklists
rdm gap 62304_2015_class_b documents/*.md          # exit 0 = fully covered; 3 = gaps (listed)
rdm gap --coverage 62304_2015_class_b documents/*.md   # per-checklist coverage table
rdm gap --coverage -v 62304_2015_class_b documents/*.md  # …and name the missing items
```

## Shipped checklists

IEC 62304 (2006/AMD1:2015 × class A/B/C), ISO 14971 (2007/2019), FDA software
guidances (2005/2021 basic + enhanced), FDA cybersecurity (2018), FDA human
factors (2011), and `part11_document_control` — the 21 CFR Part 11
electronic-records/signatures controls for a git-based
[document control system](document-control.md).

## Custom checklists

A checklist is a plain text file: one `KEY description` per line, `#` comments,
and `include <other-checklist>` directives so lists compose (a built-in name or
a path works in both `include` lines and on the command line):

```
include 62304_2015_class_b
QMS-1 our additional internal requirement
```

Full format details: [Audit checklist format](checklist-format.md).

## In CI

Run gap analysis as a required check so a document set can't merge with a
dangling clause:

```yaml
- run: rdm gap part11_document_control documents/document_control_procedure.md
```

The [worked example](https://github.com/scope-impact/rdm/tree/main/examples/github-document-control)
uses exactly this as an acceptance criterion (its DI-5), including a
falsifiability test that proves the audit actually detects gaps.
