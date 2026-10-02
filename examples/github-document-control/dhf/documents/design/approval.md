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

# Approval — Design

The approval path: what a change must pass before it reaches the default
branch. Every control here is configuration code applied to the service
provider (GitHub), and every one is verified against that code.

## Design Outputs

- `.github/rulesets/controlled-documents.json` — the branch ruleset (DI-1, DI-9).
- `.github/CODEOWNERS` — routes controlled paths to the quality team (DI-1).
- `.github/settings.json` + `setup.sh` — merge behavior as code, applied and
  drift-checked (DI-6).
- `.github/workflows/design-controls.yml` — RDM's reusable gates and the
  record checks on every pull request (DI-9).
- `.github/workflows/drift-audit.yml` — `setup.sh --check`, daily (DI-11).

Each design input is one acceptance criterion, verified by a test tagged
`@allure.story("DI-n")` in `tests/acceptance/test_approval.py`.
