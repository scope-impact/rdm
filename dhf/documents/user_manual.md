---
id: IFU-001
kind: manual
revision: 5
title: "Instructions for use (IFU) — RDM's user manual"
# The pages of the manual in reading order, from the project root (item-07-005). The "RDM on RDM" pages are not here: they publish this DHF.
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
  - docs/use/spec-and-tests.md
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
- **Requirements:** what the manual must hold is stated as requirements
  of the publishing context (item-07-005, item-07-006, item-07-007), verified like any other.
- **Changing this list:** adding or removing a page changes this document,
  so its revision is bumped.
