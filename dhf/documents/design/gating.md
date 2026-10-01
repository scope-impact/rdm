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
    depends_on: [DI-19]
  - id: DI-21
    text: "RDM shall provide a mutation probe that applies a one-line source mutation, runs a test, reports whether the test caught it (killed) or not (survived) — counting only a genuine test failure as a kill; a run that errors or collects no tests is reported as an error, never as a kill — and always restores the file: the original is journaled beside the file before mutating so an interrupted probe is recovered on the next probe of that file, a termination signal during the probe still restores, and every write invalidates the bytecode cache so a same-second size-preserving mutation cannot run stale."
    traces_to: [UN-009]
  - id: DI-26
    text: "rdm hooks shall install only the design-gate pre-commit hook by default, adding the issue-reference hooks solely when requested via an explicit flag."
    traces_to: [UN-002]
  - id: DI-27
    text: "RDM shall record faithfulness verdicts with their executed mutation probes as structured data, support replaying the recorded killing probes — failing when any probe no longer kills or can no longer execute, with a per-probe error reported as a gate failure rather than aborting the replay — and support filtering the faithfulness report to non-faithful inputs."
    traces_to: [UN-009]
    depends_on: [DI-20, DI-21]
  - id: DI-28
    text: "RDM shall pin each faithfulness verdict at a recorded hash scope, module scope by default (the full source files containing the verifying tests) with function scope selectable, and judge staleness per verdict using its recorded scope, honoring legacy verdicts as function-scoped."
    traces_to: [UN-009]
    depends_on: [DI-20]
  - id: DI-35
    text: "RDM shall let a design input declare the design inputs it depends_on; a dependent's faithfulness pin shall cover the text of every input it transitively depends on, so a change to an upstream input's text makes the verdicts of its downstream inputs stale while leaving unrelated inputs' verdicts current, tolerating dependency cycles; and the design gate shall warn on a dependency naming an undeclared design input."
    traces_to: [UN-009]
    depends_on: [DI-28]
  - id: DI-36
    text: "RDM shall record, with each new faithfulness verdict, normalized fingerprints of the design-input text and of the verifying-test source that ignore whitespace, comments, and docstrings; classify each stale verdict as A (formatting- or comment-only change), B (verifying-test change), C (requirement-text change), or D (unclassifiable: no fingerprints on record), showing the class in the faithfulness report; and let rdm story verdict --carry-forward re-pin a stale verdict without a new review only when it classifies as A, recording the carry-forward and its class in the verdict, and refusing every other class."
    traces_to: [UN-009]
    depends_on: [DI-28, DI-20, DI-35]
  - id: DI-38
    text: "RDM shall provide rdm story gate-selftest, which builds a synthetic fully-passing DHF in a scratch git repository, confirms the release gate passes it, then injects each fault class in isolation (an untested design input, a failing test, an unreviewed, unfaithful, partial, and stale verdict, a user need no input traces to, an uncommitted design-document edit, a broken journal, and a reworded locked design input) and confirms the release gate blocks each; it shall report caught or missed per fault and exit non-zero if the clean baseline is blocked or any fault escapes."
    traces_to: [UN-003]
    depends_on: [DI-3, DI-19, DI-34, DI-37]
  - id: DI-39
    text: "RDM shall carry negative knowledge across faithfulness reviews: recording a verdict over an earlier one shall preserve that verdict's surviving probes and uncovered clauses, together with any findings it had already carried, in a prior_findings list whose entries name the earlier verdict and reviewer; the review worklist (rdm story faithfulness --stale) shall print each listed input's prior findings; and prior findings shall never by themselves block a release."
    traces_to: [UN-009]
    depends_on: [DI-20, DI-27]
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
  a shell timeout still restores in-process; (c) every write advances the
  file's mtime to a **fresh whole second**, strictly beyond both the previous
  value and the clock — CPython's pyc key is (mtime truncated to seconds,
  size), so a nanosecond-granularity bump within the same second is invisible
  to it (an independent review proved the earlier unique-ns scheme let a
  same-second size-preserving mutant run stale bytecode); the whole-second
  advance changes the key on every write without the cold-cache-per-probe
  slowdown that caused the timeout incident. The verdict discrimination is strict: only a genuine
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
- **DI-35 (selective invalidation)** — a design input may declare
  `depends_on: [DI-…]` in its frontmatter entry. The faithfulness pin of a
  dependent folds in the text of every input in its transitive upstream
  closure, so rewording an upstream input re-opens the reviews downstream of
  it — and only those (an unrelated input's pin is untouched). The closure is
  cycle-safe. An input with no `depends_on` hashes exactly as before, so the
  mechanism causes no retroactive staleness. The design gate warns on a
  dependency naming an undeclared input. Borrowed from the Phoenix
  architecture's clause → canon → implementation-unit invalidation walk.
  Refines UN-009.
- **DI-36 (change classification)** — every new verdict also records two
  normalized fingerprints: of the requirement text (whitespace-collapsed) and
  of the verifying-test source (Python: the AST with docstrings removed, so
  comments, formatting and docstrings vanish; other languages: comment lines
  dropped and whitespace collapsed). A stale verdict is then classified:
  **A** both fingerprints still match (formatting/comment-only change),
  **B** only the test fingerprint moved, **C** the requirement fingerprint
  moved (dominates B), **D** the verdict predates fingerprints. The report
  prints the class beside each stale input. `rdm story verdict DI-n
  --carry-forward` re-pins a class-A verdict without a new review, recording
  `carried_forward: {class, from_hash}` (and, via DI-34, a journal event);
  it refuses B, C and D — those need a reviewer. A typo fix no longer forces
  a full §820.30(e) re-review, but it is still a recorded act. Refines UN-009.
- **DI-38 (gate self-test)** — `rdm story gate-selftest` measures the release
  gate itself (the Phoenix "fault-inject the trust surface" idea). It builds a
  synthetic, fully-passing DHF in a scratch git repository, asserts the gate
  passes it (precision: no false block), then injects each fault class in
  isolation into a fresh copy and asserts the gate blocks it (recall). It
  prints a caught/missed table and exits non-zero on a blocked baseline or an
  escaped fault. CI runs it. Refines UN-003.
- **DI-39 (negative knowledge)** — recording a verdict over an earlier one
  carries that verdict's SURVIVED probes and uncovered clauses (plus whatever
  it had itself carried) into `prior_findings`, each entry naming the earlier
  verdict and reviewer. `rdm story faithfulness --stale` prints them under each
  worklist item so the next reviewer starts from known gaps instead of
  rediscovering them. Prior findings are history, not status: they never block
  a release on their own. Refines UN-009.

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
- **Selective invalidation** (`rdm/record/faithfulness.py` `upstream_closure`,
  folded into `current_hashes`; `depends_on` read by `rdm/record/sdd.py`) —
  DI-35.
- **Change classification** (`rdm/record/fingerprint.py`;
  `faithfulness.classify_change`; `record_verdict(carry_forward=True)` /
  `rdm story verdict --carry-forward`) — DI-36.
- **Gate self-test** (`rdm/story_audit/gate_selftest.py`,
  `rdm story gate-selftest`) — DI-38.
- **Negative knowledge** (`record_verdict` carries `prior_findings`; printed by
  `story_faithfulness_command --stale`) — DI-39.

Acceptance criteria are verified by `@allure.story("DI-2" / "DI-3" / "DI-19" /
"DI-20" / "DI-21")` tests.
