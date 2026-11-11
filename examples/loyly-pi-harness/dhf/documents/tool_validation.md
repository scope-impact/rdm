---
id: CSV-HAR-001
title: Tool validation — Löyly's pi harness (computer software assurance)
status: proposed
references: [SDS-HAR-001, RMF-HAR-001, VVP-001, DC-HAR-001]
---

# Tool validation — Löyly's pi harness

**Proposed, not yet approved.** The validation of Löyly's pi harness as a
tool, an agentic software factory that regenerates software from its spec,
and of the record system that keeps that validation (git, GitHub and RDM).
It is validated as software used in production and in the quality system,
not as a medical device: the approach is computer software assurance, risk
based ([[13485:4.1.6-procedure]]). This document is the procedure for this
tool; RDM keeps its record and runs its gates.

## Intended use

The pi harness takes a stamped, approved spec and a pinned source cut,
proves a clean room, has a builder regenerate the code from the spec alone,
gates the candidate, and grades it against the spec's checklist with approved
graders. Its output, a regenerated candidate and its scoreboard, is used to
decide whether a regeneration of a product's software may proceed: it is
used directly in production of software, and its scoreboard is a quality
record ([[CSA:intended-use]], [[13485:7.5.6-software]]).

## Process risk

A failure of the harness to perform as intended could let code that does not
meet its spec be accepted: a candidate scored on what it copied from a prior
one, a score from an unapproved grader, a requirement joined to the wrong
one across cycles, a golden moved to hide a failure. Where its output enters
a regulated product without independent verification, that compromises
product quality: **high process risk** ([[CSA:risk]]). The hazards, their
evaluation and their controls are RMF-HAR-001, each control a requirement of
the harness's own spec where one exists. The analysis covers every factor the
guidance names (its §V.A(2)), for failures that are reasonably foreseeable:

| Factor | Risks |
|---|---|
| Configuration and management | RISK-HAR-001 (a leaky clean room), RISK-HAR-005 (a claim lost by the bridge) |
| Security | RISK-HAR-004 (a golden moved to hide a failure), RISK-HAR-010 (prompt injection steers the builder) |
| Data integrity | RISK-HAR-002 (an unapproved grader), RISK-HAR-003 (a reused item identity), RISK-HAR-007 (the builder games the measure), RISK-HAR-008 (the grading agent misjudges) |
| Data storage, data transfer | as for RDM, the record system (DC-HAR-001; RDM's RISK-TOOL-009, RISK-TOOL-010) |
| Operation error | RISK-HAR-006 (a commit other than the one validated), RISK-HAR-009 (the model changes under the same name) |

## AI in the harness

The harness drives two AI agents: the builder, which regenerates the code,
and, for spec-v2 cycles, the grading agent, which judges each item. Neither is
deterministic, so neither is what this validation validates: it validates
the deterministic harness around them, the clean room, the gate, the
checklist and the scoreboard, which every candidate and every verdict must
pass through. The guidance's risk framework applies to artificial
intelligence and machine learning tools used this way (its §V.A, printed
p. 6). The AI-specific risks are RMF-HAR-001's RISK-HAR-007 (the builder
games the measure), RISK-HAR-008 (the grading agent misjudges: the weakest
control, since nothing is sealed or approved for it), RISK-HAR-009 (the model
changes under the same name) and RISK-HAR-010 (prompt injection steers the
builder).

## Assurance activities

Proportionate to that risk ([[CSA:assurance]], [[13485:4.1.6-proportionate]]):

- **Scripted, automated testing.** Each of the 201 requirements of the
  harness's spec (stamped Given/When/Then scenarios) is verified by the
  harness's own acceptance suites, run unchanged; RDM traces every run to its
  requirement from the Allure results and blocks while any requirement lacks
  a passing run, any need is unaddressed, or any risk control is unverified.
- **Risk-based focus.** The requirements that control a risk of RMF-HAR-001
  (the fence, grader approval, item identities, golden moves) count only once
  verified and the risk's residual is evaluated again.
- **Unscripted assurance.** An operator runs a regeneration cycle through
  each user need and approves the result; each approval is a validation
  record in `dhf/validation/`, named for the need.
- **Ongoing monitoring.** Every cycle's scoreboard, compared with the last
  measured cycle, shows a regression the tests did not.

Beside its own tests, the assurance relies on what the harness keeps
itself ([[CSA:assurance-considerations]]): its event store and the run
evidence of each cycle, and Löyly's own review of the spec it is built from.

## Record

The record of each validation ([[CSA:record]], [[13485:4.1.6-records]]) is
the evidence bundle RDM writes from the run: the verification report (the
Löyly commit validated, who and what ran the tests and where, each
requirement with its runs, the issues found, and whether the evidence is
release-grade), the traceability matrix, the verification data and the
results themselves with a manifest and one SHA-256 over them. The conclusion
is the release gate's verdict; the approval, the reviewed pull request that
records it.

## The record system and Part 11

The validation record is required as evidence of a validated state, and it
is kept electronically, so it is a Part 11 electronic record and the system
that keeps it is held to Part 11 ([[CSA:part11]]). That system (git as the
document control system, GitHub as its service provider, RDM's gates) is
validated the same way: its intended use is to keep and gate this record;
a failure could let an unverified requirement or an unreviewed change into
the record (high process risk, the same reasoning as above); its assurance is
RDM's own acceptance tests of every gate it runs, and its controls are mapped
to Part 11 in DC-HAR-001.

## Change

The harness is validated before first use and again after every change
([[13485:4.1.6-before-use]], [[CSA:change]], [[13485:7.5.6-software]]): a new Löyly commit is a change
to the `loyly` submodule, which reruns every suite and every gate in CI, and
the evidence is valid only for the commit it tested (a run of another commit
is stale evidence).
