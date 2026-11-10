---
id: SDS-AP-001
title: "Approval — Design"
kind: design
context: approval
references: [SDS-SYS-001]
---

# Approval — Software Design

## Purpose

The approval path: what a change must pass before it reaches the default
branch — a pull request, a code owner's approval, signed commits, the required
checks, a history that cannot be rewritten — and the audit that keeps the
live repository matching what is declared. Every control is configuration
applied to the service provider (GitHub).

## Design Outputs

The design outputs are this context's components: what each is responsible
for, and which design inputs it meets.

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Branch ruleset | What a change must pass to reach the default branch | item-01-001, item-01-003 |
| Code owners | Routes every controlled path to the quality team for review | item-01-001 |
| Merge settings | Only merge commits, so the reviewed SHA is the one kept; branches deleted on merge | item-01-002 |
| Settings applier | Applies the ruleset and merge settings to the repository, and checks them for drift | item-01-002, item-01-004 |
| Design-controls workflow | Runs RDM's gates and the record checks on every pull request: the required checks | item-01-003 |
| Drift audit | Compares the live settings with the declared ones daily, and fails on a difference | item-01-004 |

GitHub enforces the branch ruleset, which requires review from the code
owners and the checks of the design-controls workflow. The settings applier
applies the ruleset and the merge settings and, run by the drift audit,
checks them. The design-controls workflow runs RDM's acceptance tests, verify and
release gate, and holds the procedure to the Part 11 checklist: this
context's part of `records`' item-02-003.

## Dependencies

Depends on GitHub (the service that enforces the configuration) and RDM (the
gates the required checks run). `release` verifies a release with the same
gates as the design-controls workflow.
