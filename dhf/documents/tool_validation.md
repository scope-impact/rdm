---
id: CSV-001
title: Tool validation — RDM (computer software assurance)
status: proposed
references: [VVP-001, RMF-001, DC-001]
---

# Tool validation — RDM

**Proposed, not yet approved.** The validation of RDM as a tool: software
used as part of a regulated team's quality management system, not a medical
device. The approach is computer software assurance, risk based
([[13485:4.1.6-procedure]]); this document is the procedure for RDM, and
RDM's own record and gates keep its evidence. A team that adopts RDM
validates it for its own intended use; this record is the supplier's
assurance it can rely on.

## Intended use

RDM reads a team's spec, checklists, risks, documents and executed test
results; reports the checklist clauses no document references (gap
analysis); decides whether a release may go ahead (the release gate); and
generates the verification report and the evidence bundle, which are quality
records. It is used directly as part of the quality management system: it
automates a quality process (the release decision) and maintains quality
records ([[CSA:intended-use]]). It does not produce the product itself, so
ISO 13485 7.5.6 applies to it only where a team uses its gates inside
production.

## Process risk

A failure of RDM to perform as intended could let a release go ahead with a
requirement not verified, a risk not evaluated, or evidence that does not
describe what was tested: RDM determines acceptability with limited
additional human review where a team relies on its gate alone. That is a
quality problem that could foreseeably compromise safety: **high process
risk** ([[CSA:risk]], [[13485:4.1.6-proportionate]]). The ways it could fail,
their evaluation and their controls are RMF-001, RDM's tool-risk register,
each control a requirement of RDM's own spec.

## Assurance activities

Proportionate to that risk ([[CSA:assurance]]):

- **Scripted, automated testing.** Each of RDM's 44 requirements (stamped
  Given/When/Then scenarios in `.pi/spec/`) is verified by an acceptance test
  that claims it; RDM's own release gate blocks while any requirement lacks a
  passing run, any user need is unaddressed, or any risk control is
  unverified.
- **Risk-based focus.** The requirements that control a risk of RMF-001 (the
  release gate, the read-only agent server, the served graph) count only once
  verified and the risk's residual is evaluated again.
- **Scenario testing on a real product.** The worked example runs RDM's gates
  over Löyly's pi harness (201 requirements, six clojure.test suites, a spec
  in another repository) on every change to RDM, beside RDM's own record.
- **Ongoing monitoring.** Every change runs every gate in CI, on RDM's own
  record (dogfooding) and on the example; a gate that stops passing stops the
  change.

The assurance also relies on ([[CSA:assurance-considerations]]): the
dependencies pinned in `uv.lock`, the RDM container image built from them,
and the executed results RDM keeps as evidence rather than screenshots.

## Record

The record of each validation ([[CSA:record]], [[13485:4.1.6-records]]) is
the evidence bundle the design-controls workflow writes from each run: the
verification report (the commit validated, who and what ran the tests and
where, each requirement with its runs, the issues found, and whether the
evidence is release-grade), the traceability matrix, the verification data
and the results with a manifest and one SHA-256 over them. The conclusion is
the release gate's verdict; the approval, the reviewed pull request.

## The record system and Part 11

The validation record is required as evidence of a validated state and is
kept electronically, so it is a Part 11 electronic record and the system that
keeps it is held to Part 11 ([[CSA:part11]]): git as the document control
system, GitHub as its service provider and RDM's gates, mapped control by
control in DC-001. That system is validated by this same procedure: RDM's
gates are what the assurance above tests.

## Change

RDM is validated before a release is first used and again after every change
([[13485:4.1.6-before-use]], [[CSA:change]]): every pull request runs every
gate before it can merge; a release is a tag, and a team adopting RDM pins
its CI to one release (the reusable workflow and the image, item-04-004), so
a new RDM release reaches a team only when it changes the pin, and the team
assesses that change for its own use ([[13485:7.5.6-software]] where RDM is
used in production).
