---
id: SDS-RL-001
title: "Release — Design"
kind: design
context: release
references: [SDS-SYS-001]
design_inputs:
  - id: DI-3
    text: "A document release shall be triggered by pushing a release tag and shall attach both human-readable rendered copies and a complete electronic archive of the document set to the release."
    traces_to: [UN-003]
  - id: DI-8
    text: "Each document release shall produce a device history record: a manifest recording the tag, commit SHA, releasing actor, timestamp, and artifact list, attached to the release alongside the copies."
    traces_to: [UN-005]
  - id: DI-10
    text: "A document release shall be published only if the release gate passes on the released commit, and shall attach that commit's verification evidence (the evidence bundle and its verification report) with the copies."
    traces_to: [UN-003, UN-005]
---

# Release — Software Design

## Purpose

A release: the human-readable copies and the electronic archive of the
document set, the device history record of who released what and when, and
the verification evidence of the released commit — published only when that
commit is verified.

## Design Outputs

The design outputs are this context's architecture, in the C4 model of the
architecture workspace (`rdm c4 draw`): its components, what each is
responsible for, and how they relate. The workspace maps each component to
what implements it.

![Components: release](../../c4/views/C3_release.svg)

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Release workflow | On a tag: verifies the release, renders the copies, writes the device history record, publishes the release | DI-3, DI-8, DI-10 |

GitHub runs the release workflow on a release tag. It verifies the commit
with the same gates as the design-controls workflow and refuses to publish
when the release gate fails; renders the copies with `records`' rendering
(which realises part of DI-3); writes the evidence bundle with RDM; and
publishes the copies, the archive, the device history record and the
evidence to the GitHub release.

## Dependencies

Depends on `records` (the documents and their rendering), `approval` (the
gates), GitHub and RDM. Nothing depends on it.
