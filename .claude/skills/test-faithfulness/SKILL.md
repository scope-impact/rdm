---
name: test-faithfulness
description: Independently review whether each design input's verifying test actually verifies it (not a hollow, tautological, or gamed assertion), and record a faithfulness verdict for `rdm story faithfulness` / the release gate to enforce. Use after tests are written/changed for a DHF, to produce the §820.30(e) design-review evidence that "the tests pass AND mean something". The reviewer must be independent of whoever authored the test.
---

# Test faithfulness review

Confirm that each design input's `@allure.story("DI-…")` test genuinely
**verifies the requirement**, then record a verdict. A passing test proves
something ran; it does not prove the test checks the right thing. This review is
the guard against an agent (or human) making the assertion pass without meeting
the input — and it is the agentic-era form of the §820.30(e) design review.

> **Independence is the point.** Do not review a test you authored. When an agent
> wrote the requirement, the code, and the test, a *different* reviewer (a second
> agent or a human) must do this. Record who reviewed in `--reviewer`.

## Inputs

- A **DHF** (default `dhf/`) whose per-context design documents declare
  `design_inputs` (each with `id`, `text`, `traces_to`).
- The **test suite** (`tests/`) where the verifying tests are tagged
  `@allure.story("DI-…")`.

## Procedure

For **each** declared design input (`rdm story faithfulness --dhf <dhf>` lists
them and their current state):

1. **Read the input text** — what must the design do? Note the measurable claim.
2. **Read the verifying test(s)** — the function(s) tagged `@allure.story("DI-…")`.
   Read the *body*, not just the name.
3. **Judge faithfulness adversarially.** Try to make the case that the test would
   still pass if the requirement were violated. Mark it **unfaithful** (or
   `weak`) if any of these hold:
   - it asserts a tautology or a constant, not the behaviour (e.g. checks a
     string's wording instead of that the behaviour produces it);
   - it exercises a stub/mock where the real path is what the input is about;
   - it would pass under an obviously broken implementation;
   - it tests a different, narrower thing than the input claims;
   - the input is untestable as written (push back — the input needs sharpening).
   Otherwise mark it **faithful**, and say *why* it would catch a violation.
4. **Record the verdict** (computes the current hash and pins to it):

   ```bash
   python .claude/skills/test-faithfulness/scripts/write_verdict.py \
     --dhf dhf --design-input DI-1 --verdict faithful \
     --reviewer "claude (independent of author)" \
     --reviewed-tests test_compile_verification_from_the_record \
     --rationale "drives build_verification over a real registry+results and asserts the grouped DI→UN shape; a no-op implementation would fail it"
   ```

5. **Re-check** with `rdm story faithfulness --dhf <dhf>` — every input should be
   `faithful`. The release gate (`rdm story release-gate`) then blocks unless
   every input is both *verified* (test passed) and *faithful* (this review).

## Notes

- A verdict is pinned to a hash of the input text + the verifying-test source. If
  the test (or the input) later changes, the verdict goes **stale** and must be
  redone — the same "an edit re-opens approval" rule the design gate uses.
- Prefer **fixing a weak test** over recording `unfaithful` and stopping: an
  `unfaithful`/`weak` verdict blocks release by design. The honest loop is
  *find weak test → strengthen it → re-review → faithful*.
- Do not rubber-stamp. A faithful verdict with a vacuous rationale is worse than
  none: it converts an open question into false assurance.
