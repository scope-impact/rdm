# Document control — RDM's own record lives in git

RDM does not just *recommend* git as a document control system — it makes the
claim for its own record and holds itself to it on every push. The controlled
statement is
[`dhf/documents/document_control.md`](https://github.com/scope-impact/rdm/blob/main/dhf/documents/document_control.md)
(DC-001): RDM's Design History File, design documents, faithfulness verdicts,
and the source that realises them are controlled **in git, with GitHub as the
service provider**.

## The claim is executable

Design input **DI-25** makes it machine-checked rather than aspirational:

- RDM ships a **21 CFR Part 11 document-control checklist** as a built-in —
  the electronic-records / electronic-signatures controls applicable to a
  git-based document control system (§11.10 a–k, §11.50, §11.70, §11.100):

  ```bash
  rdm gap part11_document_control your-document-control-sop.md
  ```

- RDM's own statement must pass gap analysis against that same checklist —
  zero missing items — enforced by a tagged acceptance test in CI. The
  checklist offered to downstream projects is the one RDM's record is held to.

## How each control is met

The mechanisms are largely RDM's own features, so the mapping is circular in
the best way:

| Part 11 control | Mechanism |
|---|---|
| 11.10(a) validation, altered-record discernment | controls are enforced code (gate, hook, CI), verified by tagged tests; any change produces a new SHA, and hash-pinned verdicts go stale on edits |
| 11.10(b)/(c) copies, retention | rendered site + matrix via `rdm render`; `git archive` at any tag; every clone is a full replica |
| 11.10(d)/(g) access, authority | GitHub org membership + 2FA; PR approval into the default branch; verdict reviewer independent of the test author |
| 11.10(e) audit trail | the git history: SHA-chained, time-stamped, author-attributed |
| 11.10(f) sequencing | the design-controls loop itself: hook blocks implementation before design approval; CI gates in order |
| 11.10(i) training | `dhf/AGENT_WORKFLOW.md`, loaded automatically at agent session start |
| 11.50 / 11.70 / 11.100 signatures | the PR review (name, UTC time, APPROVED meaning), bound to the content-addressed SHA, one account per individual |

## Try the full pattern

The worked example
[`examples/github-document-control`](https://github.com/scope-impact/rdm/tree/main/examples/github-document-control)
is a complete record-first project built on this claim — GitHub rulesets as
design outputs, PR approval as the Part 11 electronic signature, DMR/DHR
analogs, and a drift-audit script — and `rdm adopt` lays the same control
surface into any existing repository.

## The evidence

The [traceability matrix](traceability-matrix.md) on this site is **generated
from a live acceptance run at docs build time** — the published page is
itself an accurate copy (§11.10(b)) of the current verification record.
