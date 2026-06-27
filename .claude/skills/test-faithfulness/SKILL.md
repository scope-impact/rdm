---
name: test-faithfulness
description: Independently review whether each design input's verifying test actually verifies it (not a hollow, tautological, gamed, or PARTIAL assertion), and record a faithfulness verdict for `rdm story faithfulness` / the release gate to enforce. Use after tests are written/changed for a DHF, to produce the §820.30(e) design-review evidence that "the tests pass AND mean something". The reviewer must be independent of whoever authored the test.
---

# Test faithfulness review

Confirm that each design input's `@allure.story("DI-…")` test genuinely
**verifies the whole requirement**, then record a verdict. A passing test proves
something ran; it does not prove the test checks the right thing — nor that it
checks *all* of the requirement. This review is the guard against an agent (or
human) making the assertion pass without meeting the input, and it is the
agentic-era form of the §820.30(e) design review.

> **Independence is the point.** Do NOT review a test you authored. When an agent
> wrote the requirement, the code, and the test, a *different* reviewer (a second
> agent or a human) must do this — a self-review reliably over-rates its own
> tests. Judge **only what the assertions actually check**: ignore the test's
> name and docstring (they state *intent*, which can lie). Record who reviewed in
> `--reviewer`.

## Inputs

- A **DHF** (default `dhf/`) whose per-context design documents declare
  `design_inputs` (each with `id`, `text`, `traces_to`).
- The **test suite** (`tests/`) where the verifying tests are tagged
  `@allure.story("DI-…")`.

## Procedure

For **each** declared design input (`rdm story faithfulness --dhf <dhf>` lists
them and their current state):

1. **Decompose the requirement into atomic clauses.** Split the design-input
   `text` on every "and" / list / qualifier into the separate verifiable claims
   it makes. Most overclaiming hides here: a 3-clause requirement met by a
   2-clause test. Example —
   *"ship built-in checklists | for the standards | resolving `include` directives"*
   is **three** clauses, not one.

2. **Build a clause-coverage table** from the test *body* (the assertions only):

   ```
   clause                         covered by                         falsified by (mutation)
   ─────────────────────────────  ─────────────────────────────────  ─────────────────────────
   ships built-in checklists      assert "62304…" in listed          delete the checklist file
   for the standards              assert 3 names present             —
   resolving include directives   ✗ NOT EXERCISED                    (would still pass!) ← gap
   ```

3. **For every covered clause, name the mutation that breaks it** and confirm the
   test would FAIL under that mutation. "It would catch a violation" is not a
   belief — state the concrete one-line change to the implementation (or input)
   that violates the clause, and check the assertion that goes red. If you cannot
   name a mutation the test catches, the clause is **not** covered.

4. **Decide the verdict — `faithful` only if EVERY clause is covered:**
   - **faithful** — every clause has a covering assertion with a named failing
     mutation.
   - **unfaithful** (or `weak`) — any clause is tautological, exercises a
     stub/mock instead of the real path, would survive its mutation, tests
     something narrower than the clause, **or any clause is uncovered**. Partial
     coverage is unfaithful, not "mostly faithful".
   - If a clause is genuinely covered by a *different* tagged test, say so
     explicitly in the rationale (e.g. "render clause covered by DI-…"); do not
     silently drop it.
   - If the input is untestable as written, push back — the *input* needs
     sharpening, not a generous verdict.

5. **Record the verdict** with the installed `rdm` binary (this skill depends on
   `rdm`, not a bundled script). It computes the current verifying-test source
   hash and pins the verdict to it. The rationale MUST name the per-clause
   failing mutation(s) — a verdict whose rationale does not is not a real review.
   For partial coverage, pass the uncovered clause(s) with `--uncovered` (the
   gate downgrades to `partial` and blocks):

   ```bash
   rdm story verdict DI-11 --dhf dhf --verdict faithful \
     --reviewer "claude (independent of author)" \
     --reviewed-tests test_ships_composable_builtin_checklists \
     --rationale "clauses: ships(✓ name asserted), standards(✓ 3 names), includes(✓ a key defined only in an included file is required — partial doc→exit3, full→0; a broken include-resolver would pass the partial doc)"
   ```

   Requires `rdm` on PATH (`pip install rdm[story-audit]`).

6. **Re-check** with `rdm story faithfulness --dhf <dhf>` — every input should be
   `faithful`. The release gate (`rdm story release-gate`) then blocks unless
   every input is both *verified* (test passed) and *faithful* (this review).

## Notes

- A verdict is pinned to a hash of the input text + the verifying-test source. If
  the test (or the input) later changes, the verdict goes **stale** and must be
  redone — the same "an edit re-opens approval" rule the design gate uses.
- Prefer **fixing a weak/partial test** over recording `unfaithful` and stopping:
  an unfaithful verdict blocks release by design. The honest loop is
  *find the uncovered clause → strengthen the test (or add a tagged test) →
  re-review → faithful*.
- Do not rubber-stamp. A faithful verdict with a vacuous rationale — or one that
  silently ignores an uncovered clause — is worse than none: it converts an open
  question into false assurance. This is exactly the failure an independent
  reviewer exists to prevent.
