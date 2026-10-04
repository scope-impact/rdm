---
id: IFU-001
kind: manual
revision: 4
title: "Instructions for use (IFU) — RDM's user manual"
# The pages of the manual in reading order, from the project root (DI-77,
# DI-80). The "RDM on RDM" pages are not here: they publish this DHF.
pages:
  - docs/index.md
  - docs/about/intended-use.md
  - docs/about/how-rdm-works.md
  - docs/about/plan-vs-record.md
  - docs/about/safety.md
  - docs/install/installation.md
  - docs/install/getting-started.md
  - docs/use/index.md
  - docs/use/changing-the-record.md
  - docs/use/design-inputs-and-tests.md
  - docs/use/risk-register.md
  - docs/use/validation-evidence.md
  - docs/use/gates.md
  - docs/use/gap-analysis.md
  - docs/use/ci.md
  - docs/use/graph.md
  - docs/use/graph-explorer.md
  - docs/use/agents.md
  - docs/use/documents.md
  - docs/troubleshooting.md
  - docs/reference/index.md
  - docs/reference/cli.md
  - docs/reference/api.md
  - docs/reference/glossary.md
  - docs/reference/worked-example.md
  - docs/revision-history.md
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
