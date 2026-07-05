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
  - id: DI-21
    text: "RDM shall provide a mutation probe that applies a one-line source mutation, runs a test, reports whether the test caught it (killed) or not (survived) — counting only a genuine test failure as a kill; a run that errors or collects no tests is reported as an error, never as a kill — and always restores the file: the original is journaled beside the file before mutating so an interrupted probe is recovered on the next probe of that file, a termination signal during the probe still restores, and every write invalidates the bytecode cache so a same-second size-preserving mutation cannot run stale."
    traces_to: [UN-009]
  - id: DI-26
    text: "rdm hooks shall install only the design-gate pre-commit hook by default, adding the issue-reference hooks solely when requested via an explicit flag."
    traces_to: [UN-002]
  - id: DI-27
    text: "RDM shall record faithfulness verdicts with their executed mutation probes as structured data, support replaying the recorded killing probes — failing when any probe no longer kills or can no longer execute, with a per-probe error reported as a gate failure rather than aborting the replay — and support filtering the faithfulness report to non-faithful inputs."
    traces_to: [UN-009]
  - id: DI-28
    text: "RDM shall pin each faithfulness verdict at a recorded hash scope, module scope by default (the full source files containing the verifying tests) with function scope selectable, and judge staleness per verdict using its recorded scope, honoring legacy verdicts as function-scoped."
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
- **DI-21 (mutation probe)** — provide `rdm story mutation-probe` to apply a
  one-line source mutation, run a test, report killed/survived, and always
  restore the file. Turns "this test would catch a broken X" from a reviewer's
  claim into executed evidence. The restore guarantee is defended in depth
  (each layer added after an incident proved the previous one insufficient):
  a `finally` restore alone dies with the process, so (a) the original is
  **journaled** to a sidecar before mutating and any leftover journal is
  recovered at the start of the next probe of that file — surviving even
  SIGKILL; (b) SIGTERM during the probe window is converted to an exception so
  a shell timeout still restores in-process; (c) every write bumps the file's
  mtime by a unique nanosecond value, so CPython's (mtime-seconds, size) pyc
  key can never serve stale bytecode to a same-second size-preserving
  mutation — without the cold-cache-per-probe slowdown that caused the
  timeout incident. The verdict discrimination is strict: only a genuine
  test failure counts as a kill. A run that did not execute cleanly — a
  collection error, no tests matched the selector, an internal failure —
  is an **error**, never a kill: a typo'd selector must not manufacture
  "the test caught it" evidence (an independent review found exactly this
  false-KILLED path). Refines UN-009.
- **DI-26 (design-gate-only hooks default)** — `rdm hooks` installs only the
  design-gate pre-commit hook by default; the legacy issue-reference hooks
  (commit-msg / prepare-commit-msg) are installed only with
  `--with-issue-hooks`. RDM's own repo deleted them; downstream defaults
  should match. Refines UN-002.
- **DI-27 (replayable probes)** — a verdict can carry the reviewer's executed
  mutation probes as structured data (`--probe` JSON, repeated), and
  `rdm story faithfulness --replay` re-executes every recorded killing probe,
  failing if any now survives — the review becomes continuously verifiable
  evidence, not a trust-at-review-time claim. A probe that can no longer
  execute (its file or find-text is gone, or the test run errors) is itself
  a replay failure, reported per probe — the replay must neither crash on
  the first broken probe nor count a broken run as a kill. `--stale` filters
  the report to non-faithful inputs (the reviewer's worklist). Refines UN-009.
- **DI-28 (verdict hash scope)** — each verdict records its `hash_scope`.
  Default `module`: the pin covers the full source files containing the
  verifying tests, so editing a shared helper or fixture re-opens the review
  (function-only pinning let helper edits hollow a test silently). `function`
  remains selectable for noisy files; verdicts without the field are honored
  as function-scoped (no retroactive staleness from the *scope* mechanism
  itself; widening test-file *discovery* — new conventional file-name globs,
  DI-31 — may still re-open module-scope reviews, accepted because staleness
  fails safe: it demands a re-review, it never silently passes). Refines UN-009.

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
- **Mutation probe** (`rdm/story_audit/mutation.py`, `rdm story mutation-probe`) —
  applies a one-line mutation, runs the test, reports killed/survived, always
  restores the file; the executed-evidence half of the faithfulness review.

Acceptance criteria are verified by `@allure.story("DI-2" / "DI-3" / "DI-19" /
"DI-20" / "DI-21")` tests.
