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

# Release — Design

A release: the copies, the device history record, and the verification
evidence of the released commit, published only when that commit is
verified.

## Design Inputs

- **DI-3 (release copies)** — tag-triggered release with rendered PDF copies
  and a `git archive` electronic set (§11.10(b)/(c)). Refines UN-003.
- **DI-8 (device history record)** — the DHR (§820.184 analog) is the record
  of each release: the release workflow writes a manifest (tag, commit SHA,
  releasing actor, timestamp, artifact list) and attaches it to the GitHub
  Release with the copies — so every released document set carries the record
  of who released what, when, from which exact revision. Refines UN-005.
- **DI-10 (verified releases)** — a release is evidence as well as copies: the
  release workflow runs the acceptance tests at the tag, refuses to publish
  unless the release gate passes, and attaches the evidence bundle (Allure results, verification data, the
  traceability matrix, a manifest) and its verification report (every design
  input with the runs that verify it, at the released commit) to the release. The DHR (DI-8) then names a release whose verification is attached
  to it. Refines UN-003, UN-005.

## Design Outputs

- `.github/workflows/release-documents.yml` — tag-triggered release (DI-3),
  the device-history-record manifest (DI-8), gated by the release gate with
  the evidence bundle attached (DI-10).

Each design input is one acceptance criterion, verified by a test tagged
`@allure.story("DI-n")` in `tests/acceptance/test_release.py`.
