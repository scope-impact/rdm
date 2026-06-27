---
id: SDS-GATE-001
kind: design
context: gating
satisfies: [UN-002, UN-003, UN-009]
design_inputs:
  - id: DI-2
    text: "RDM shall block the transition into implementation until design input and review are present, complete, and approved (committed) in git; a later edit re-opens the gate."
    traces_to: [UN-002]
  - id: DI-3
    text: "RDM shall block release unless every declared design input is verified by a passing test."
    traces_to: [UN-003]
  - id: DI-19
    text: "RDM shall require an independent faithfulness verdict for every design input, blocking release on any unreviewed, unfaithful, partial, or stale verdict."
    traces_to: [UN-009]
  - id: DI-20
    text: "RDM shall provide a command to record a faithfulness verdict for a design input, hash-pinned to the current verifying-test source, and reject an undeclared design input."
    traces_to: [UN-009]
---

# Gating — Software Design

## Design Inputs

This context owns:

- **DI-2 (design gate)** — block the transition into implementation until the
  per-context design documents and the design review are present, complete, and
  approved (committed) in git; a later edit re-opens the gate. Refines UN-002.
- **DI-3 (release gate)** — block release unless every declared design input is
  verified by a passing test. Refines UN-003.
- **DI-19 (faithfulness gate)** — require an independent faithfulness verdict for
  every design input; block release on any `unreviewed`, `unfaithful`, `partial`
  (a verdict listing uncovered clauses), or `stale` (test changed since review)
  verdict. Refines UN-009.
- **DI-20 (verdict recorder)** — provide `rdm story verdict <DI>` to record a
  verdict, hash-pinned to the current verifying-test source, rejecting an
  undeclared design input. (The producer command the `test-faithfulness` skill
  calls — so the skill depends on the `rdm` binary, not a bundled script.)
  Refines UN-009.

## Design Outputs

Enforces design controls and verified coverage.

- **Design gate** (`rdm/story_audit/design_gate.py`) — the per-context design
  documents and the review must be present, free of placeholders, and approved
  (committed clean) in git; an edit to an approved document re-opens the gate.
- **Pre-commit hook** (`rdm/hook_files/pre-commit`) — blocks committing
  implementation work until the design gate passes; commits of the design docs
  themselves are allowed (that commit is the approval).
- **Release gate** (`run_release_gate`) — blocks release unless every design
  input is verified by a passing test, independently confirmed faithful, and
  every user need is addressed.
- **Faithfulness gate** (`rdm/record/faithfulness.py`, `run_faithfulness_gate`) —
  reconciles design inputs against `*-faithfulness.json` verdicts into
  faithful / unfaithful / partial / stale / unreviewed; only `faithful` passes.
- **Verdict recorder** (`record_verdict`, `rdm story verdict`) — writes a verdict
  hash-pinned to the current test source; the `test-faithfulness` skill's only
  dependency on RDM (no bundled script).

Acceptance criteria are verified by `@allure.story("DI-2" / "DI-3" / "DI-19" /
"DI-20")` tests.
