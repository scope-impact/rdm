---
id: IFU-001
kind: manual
revision: 2
title: "Instructions for use (IFU) — RDM's user manual"
# The pages of the manual, from the project root (DI-77). The "RDM on RDM"
# pages are not here: they publish this DHF, which is the record, not the manual.
pages:
  - docs/index.md
  - docs/get-started.md
  - docs/record-first-architecture.md
  - docs/glossary.md
  - docs/plan-vs-record.md
  - docs/design-controls.md
  - docs/agent-workflow.md
  - docs/risk.md
  - docs/ai-persona-usability-validation.md
  - docs/gates.md
  - docs/gap-analysis.md
  - docs/reusable-ci.md
  - docs/graph.md
  - docs/graph-explorer.md
  - docs/agents.md
  - docs/authoring.md
  - docs/example-traceability-map.md
  - docs/cli.md
  - docs/reference.md
  - docs/changelog.md
---

# Purpose

RDM's instructions for use (IFU) are its user manual, the documentation site:
what anyone who uses RDM follows to keep a record the way RDM's gates
enforce. It is a design output, and this controlled document is its entry in the device-master-record index
(`rdm story dmr`): the pages listed above, at the revision of the release
they ship with.

# How it is controlled

- **Approval:** each change to a page is a reviewed pull request; the
  release tag fixes the version that ships.
- **Design inputs:** what the manual must teach is stated as design inputs
  of the publishing context (DI-79), verified like any other.
- **In the graph:** each listed page is projected (DI-77): the design inputs
  it names, so the trace of a changed input lists the pages to re-read, and
  the labels of each tagged-test example it shows, so a gate shape warns on
  an example that names no component (DI-78).
- **Changing this list:** adding or removing a page changes this document,
  so its revision is bumped.
