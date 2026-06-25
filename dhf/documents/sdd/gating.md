---
id: SDS-GATE-001
context: gating
satisfies: [UN-002, UN-003]
---

# Gating — Software Design

Enforces design controls and verified coverage.

- **Design gate** (`rdm/story_audit/design_gate.py`) — design input and review
  must be present, free of placeholders, and approved (committed clean) in git;
  an edit to an approved document re-opens the gate.
- **Pre-commit hook** (`rdm/hook_files/pre-commit`) — blocks committing
  implementation work until the design gate passes; commits of the design docs
  themselves are allowed (that commit is the approval).
- **Release gate** (`run_release_gate`) — blocks release unless every user need
  is verified by a passing test.

Contributes to **UN-002** (block transition until approved) and **UN-003**
(block release until verified). Acceptance criteria verified by
`@allure.story("UN-002")` / `@allure.story("UN-003")` tests.
