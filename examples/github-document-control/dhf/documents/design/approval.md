---
id: SDS-AP-001
title: "Approval — Design"
kind: design
context: approval
realises: [DI-5]
references: [SDS-SYS-001]
design_inputs:
  - id: DI-1
    text: "Changes to the default branch shall require a pull request with at least one code-owner approval, verified commit signatures, passing status checks, and protection against history rewriting and branch deletion, enforced by a repository ruleset kept as configuration code."
    traces_to: [UN-002, UN-004]
  - id: DI-6
    text: "Repository merge behavior shall be declared as configuration code: pull requests merge only by merge commit so the reviewed SHA is preserved in history, squash and rebase merges are disabled, head branches are deleted on merge, and the setup script shall apply and drift-check these settings against the live repository."
    traces_to: [UN-001, UN-004]
  - id: DI-9
    text: "Every pull request to the default branch shall run the design controls (the design gate, the acceptance tests, verification, the release gate and graph validation) through RDM's reusable gates pinned to a released revision, and the Part 11 gap analysis, and the ruleset shall require exactly the checks that workflow reports."
    traces_to: [UN-002, UN-004]
  - id: DI-11
    text: "A scheduled audit shall compare the live ruleset and repository settings with the checked-in configuration at least daily, and shall fail on any difference."
    traces_to: [UN-002, UN-004]
---

# Approval — Software Design

## Purpose

The approval path: what a change must pass before it reaches the default
branch — a pull request, a code owner's approval, signed commits, the required
checks, a history that cannot be rewritten — and the audit that keeps the
live repository matching what is declared. Every control is configuration
applied to the service provider (GitHub).

## Design Outputs

The design outputs are this context's architecture, in the C4 model of the
architecture workspace (`rdm c4 draw`): its components, what each is
responsible for, and how they relate. The workspace maps each component to
what implements it.

![Components: approval](../../c4/views/C3_approval.svg)

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Branch ruleset | What a change must pass to reach the default branch | DI-1, DI-9 |
| Code owners | Routes every controlled path to the quality team for review | DI-1 |
| Merge settings | Only merge commits, so the reviewed SHA is the one kept; branches deleted on merge | DI-6 |
| Settings applier | Applies the ruleset and merge settings to the repository, and checks them for drift | DI-6, DI-11 |
| Design-controls workflow | Runs RDM's gates and the record checks on every pull request: the required checks | DI-9 |
| Drift audit | Compares the live settings with the declared ones daily, and fails on a difference | DI-11 |

GitHub enforces the branch ruleset, which requires review from the code
owners and the checks of the design-controls workflow. The settings applier
applies the ruleset and the merge settings and, run by the drift audit,
checks them. The design-controls workflow runs RDM's design gate, verify and
release gate, and holds the procedure to the Part 11 checklist: this
context's part of `records`' DI-5.

## Dependencies

Depends on GitHub (the service that enforces the configuration) and RDM (the
gates the required checks run). `release` verifies a release with the same
gates as the design-controls workflow.
