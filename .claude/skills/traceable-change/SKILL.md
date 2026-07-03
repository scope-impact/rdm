---
name: traceable-change
description: The operating procedure for EVERY change to RDM's source, tests, or DHF — read BEFORE writing any code. Walks a change through the record-first loop end to end: classify it, record the design input first (committed = approved), verify it with a tagged acceptance test, implement, run the gates exactly as CI, and hand the faithfulness verdict to an independent reviewer. Use when adding a feature, fixing a bug, refactoring, or changing anything under rdm/, tests/, or dhf/.
---

# Traceable change — the operating procedure

RDM is developed under its own design controls (IEC 62304 / 21 CFR 820.30,
dogfooded — see `dhf/README.md`). Every change here must land **fully
traceable**: user need (UN-…) → design input (DI-…) → tagged acceptance test →
implementation → independent faithfulness verdict → green gates. This skill is
the canonical step-by-step; `CLAUDE.md` and `AGENTS.md` only summarize it.

## 1. Preflight

1. Bootstrap ran? `git config core.hooksPath` must print `.githooks`.
   If not: `bash contrib/agent-bootstrap.sh` (Claude sessions run it
   automatically at session start).
2. Gates green on HEAD? Run the pipeline **exactly as CI**
   (`.github/workflows/design-controls.yml`) — this block is the canonical
   copy, referenced from everywhere else:

   ```bash
   uv run rdm story design-gate --dhf dhf
   uv run pytest tests/acceptance --alluredir=dhf/allure-results
   uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
   uv run rdm story faithfulness --dhf dhf
   uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
   ```

   Red on HEAD usually means you are resuming someone's change mid-flight —
   find out whose before adding your own.
3. A Backlog.md task exists for the work, is `In Progress`, and is assigned to
   you (`backlog task edit <id> -s "In Progress" -a @me`) — see the `backlog`
   skill. CLI only, never edit `backlog/tasks/*.md` by hand.

## 2. Classify the change

`uv run rdm story trace <UN-…|DI-…> --dhf dhf` shows the existing slice
(need → inputs → tests → verdicts). User needs live in
`dhf/documents/verification_and_validation_plan.md` frontmatter; each design
doc under `dhf/documents/design/` owns its `design_inputs`.

| Your change | DHF action |
|---|---|
| A new user journey RDM must serve | New UN-n in the V&V plan frontmatter **and** validation-approach table; new DI-n in the owning context doc; update its `satisfies` list |
| New verifiable behavior under an existing UN | New DI-n in the context doc that owns the area, `traces_to` the UN. Next free id: `grep -rn "id: DI-" dhf/documents/design/` |
| Changes what an existing DI requires | Edit that DI's `text` in its owning design doc — its verdict hash-pins the test and will go **stale**; that is intended, it re-opens the review |
| Bug fix (code violates what a DI already promises) | Usually no DI edit; **strengthen the tagged test** that should have caught it → verdict goes stale → independent re-review |
| Behavior-neutral refactor | No DHF edit; do not touch tagged tests; all gates must stay green unchanged |
| Docs / tooling / process only (no `.py`) | No DHF involvement; skip to step 3.8 |

## 3. The loop, in order

1. **Design record first — committed first.** Edit the design doc(s) (and the
   V&V plan for a new UN). Commit these docs **alone**: a design-doc-only
   commit is how approval is recorded, and the pre-commit hook blocks every
   `.py` commit until the design gate is green. Confirm:
   `uv run rdm story design-gate --dhf dhf`.
2. **Acceptance test next.** In `tests/acceptance/`, tagged
   `@allure.story("DI-n")` — the test *is* the acceptance criterion ("live
   BDD"). Write it against the DI text's clauses, not the implementation. It
   may start red.
3. **Implement** until the tagged test passes.
4. **Run the full pipeline** (the §1 block). `faithfulness` will now report
   DI-n as `unreviewed` (new test) or `stale` (edited test) — expected.
5. **Faithfulness — hand off, never self-review.** You MUST NOT run
   `rdm story verdict` for a test you authored. Dispatch an **independent
   reviewer**: a separate agent given only the DI id(s) and the
   `test-faithfulness` skill (no access to your reasoning), or a human. The
   reviewer decomposes the DI text into clauses, proves coverage with
   `rdm story mutation-probe`, and records
   `rdm story verdict DI-n --verdict … --reviewer … --rationale …`, which
   writes `dhf/faithfulness/DI-n-faithfulness.json`. If the verdict comes back
   `partial`/`unfaithful`: strengthen the test (step 2) and loop.
6. **Re-run** `faithfulness` + `release-gate` — both must pass.
7. The traceability matrix is **generated**
   (`rdm render dhf/documents/traceability_matrix.md dhf/config.yml
   dhf/data/verification.yml`) — never hand-edit statuses into it.
   `dhf/allure-results/` and `dhf/data/verification.yml` are gitignored
   evidence, not committed.
8. **Whole-repo checks:** `uv run ruff check .` and the full suite
   `uv run pytest tests` (not just acceptance).
9. **Backlog wrap-up** via CLI: check the ACs, `--notes` as the PR
   description, `-s Done`.
10. **Commit implementation + test (+ verdict), push.** The pre-commit hook
    re-runs the design gate; CI re-runs the identical pipeline on the push.

## 4. Hard rules (never)

- Never bypass the gate: no `RDM_SKIP_DESIGN_GATE=1`, no
  `git commit --no-verify`, never unset or re-point `core.hooksPath`.
  (Claude sessions also enforce this mechanically via a PreToolUse hook.)
- Never hand-edit `dhf/faithfulness/*.json` — verdicts exist only via
  `rdm story verdict`.
- Never hand-write verification rows/statuses into the traceability matrix.
- Never record a verdict for a test you authored, and never feed the reviewer
  your rationale for why the test is adequate — independence is the point.
- Never edit `backlog/tasks/*.md` or `backlog/drafts/*.md` directly.
- Editing a tagged test without a fresh verdict = `stale` = red CI. That is
  the system working, not an obstacle to route around.

## 5. Recovery playbook

| Symptom | Fix |
|---|---|
| Design gate red: design doc modified/uncommitted | Commit the design doc(s) alone — that commit is the approval |
| `faithfulness` reports `stale` | The tagged test changed since review → step 3.5 (independent re-review) |
| `faithfulness` reports `unreviewed` | New DI has no verdict yet → step 3.5 |
| Mutation probe `SURVIVED` | The test is hollow for that clause — strengthen the assertion, then re-probe |
| Release gate: user need unaddressed | A UN has no design doc `satisfies` entry or no DI `traces_to` it — fix the frontmatter, not the gate |
| Pre-commit hook blocks a `.py` commit | You skipped step 3.1 — commit the design record first |
