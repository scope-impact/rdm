# Gap analysis — audit documents against a standard

`rdm gap` checks that your documents reference every clause a standard's
checklist lists. A reference is a `[[KEY]]` block in a document, such as
`[[62304:5.1.1]]` or `[[This section fulfills X-1 and X-2]]`; the checklist
maps keys to the standard's clauses. Only text inside `[[ … ]]` counts, and a
dotted descendant covers its parent. The exact reading and matching rules are
the [compliance design](dhf/documents/design/compliance.md#design-outputs).

```bash
rdm gap --list                                          # the built-in checklists
rdm gap 62304_2015_class_b documents/*.md               # exit 0 = covered; 3 = gaps, listed
rdm gap --coverage 62304_2015_class_b FDA-SW_2021_basic documents/*.md   # one row per checklist
rdm gap --coverage -v 62304_2015_class_b documents/*.md # …and name the missing clauses
```

Both exit 3 when a clause is missing, and 2 when nothing could be checked: a
checklist with no clauses, or a checklist, include or document that cannot be read. A
document that still holds a `TODO` placeholder is named in a warning: a fresh
`rdm init` project references every clause from templates that say nothing
yet, so its coverage means nothing until they are written.

The built-ins cover IEC 62304, ISO 14971, the FDA software, cybersecurity and
human-factors guidances, and `part11_document_control`: the 21 CFR Part 11
controls for document control in git. Audit your own document-control
procedure against it:

```bash
rdm gap part11_document_control documents/document_control_procedure.md
```

RDM holds [its own](dhf/documents/document_control.md) to the same checklist,
and the [worked example](https://github.com/scope-impact/rdm/tree/main/examples/github-document-control)
is a complete project built on it.

## Your own checklists

A checklist is a plain text file. Each line is a clause key followed by its
description; `#` starts a comment; `include <name>` pulls in a built-in
checklist by name or another file, relative to the including one:

```
include 62304_2015_class_b
QMS-1 our additional internal requirement
```

There is no way to exclude a clause. Either reference it from a document that
says where it is met or why it does not apply, or start from a copy: the gap
report prints the missing clauses in checklist format, so
`rdm gap 62304_2015_class_b > my_checklist.txt` gives you a checklist to edit.

## In CI

Run gap analysis as a required check, so a document set cannot merge with a
clause no document references:

```yaml
- run: rdm gap part11_document_control documents/document_control_procedure.md
```

The worked example verifies its DI-5 with exactly this, including a check
that the audit does detect a gap.
