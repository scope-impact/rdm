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

## Design Inputs

- **DI-1 (gated approval as e-signature)** — the ruleset is the enforcement of
  §11.10(f)/(g), §11.50/70/100 mechanics: independent code-owner approval,
  signed commits, required checks, immutable history. Refines UN-002, UN-004.
- **DI-6 (merge behavior as code)** — repository settings that shape the
  record are configuration code too: only merge commits (the SHA that was
  reviewed is the SHA preserved in history — the §11.70 linking argument),
  squash and rebase merges disabled (both rewrite the reviewed commits), head
  branches deleted on merge; `setup.sh` applies and drift-checks them like the
  ruleset. The drift check compares the declared values **exactly** against the
  live configuration projected onto the declared fields — a subset/containment
  test is not a drift check (jq's `contains` matches substrings and array
  subsets, so a changed value can pass unnoticed). Refines UN-001, UN-004.
- **DI-9 (the checks the ruleset requires)** — DI-1 requires passing status
  checks; DI-9 says which. The design-controls workflow calls RDM's reusable
  gates (`scope-impact/rdm/.github/workflows/gates.yml`) at a released tag, so
  every change is verified by the same pinned RDM, and runs `rdm gap` over the
  SOP. The ruleset requires the two checks that workflow reports, by their
  exact names: a required check no workflow reports blocks every merge, and a
  check the ruleset does not require can be bypassed. Refines UN-002, UN-004.
- **DI-11 (drift audit on a schedule)** — the ruleset and settings are enforced
  by GitHub, not by this repository, so an administrator can change them in
  the web UI without a pull request (RISK-DC-002). A scheduled workflow runs
  `setup.sh --check` daily with a read-only admin token and fails on any
  difference, so a weakened approval path is found within a day, and the
  failed run is the record that it was. Refines UN-002, UN-004.

It also **realises** DI-5, owned by `records`: the design-controls
workflow runs the Part 11 gap analysis on every pull request.

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
