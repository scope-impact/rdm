# Agent operating procedure — record-first design controls

This project is developed under record-first design controls (IEC 62304 /
ISO 13485 7.3 / 21 CFR 820.30) using [RDM](https://github.com/innolitics/rdm).
Whether you are an AI coding agent or a human, every change must land **fully
traceable**: user need → design input → tagged acceptance test →
implementation → independent faithfulness review → green gates. This file is
the operating procedure; follow it before writing any code.

## The record

- **User needs** (`user_needs`, in the V&V plan's YAML frontmatter) — the
  validated journeys; the coverage denominator.
- **Design inputs** (DI-…) — the verifiable *what*, declared once in the
  `design_inputs` frontmatter of the `kind: design` document that owns them
  (see `documents/software_design_specification.md`), each tracing to a user
  need via `traces_to`.
- **Acceptance criteria are tests** — each design input is verified by an
  automated test tagged `@allure.story("DI-…")`; the test *is* the acceptance
  criterion ("live BDD").
- **Approval is the version-control record** — a design document is approved
  when its change is committed (and merged through review); no separate
  sign-off block.
- **The traceability matrix is generated** (`rdm render …`) — never hand-edit
  verification statuses into it.

## Session bootstrap (once per session)

```bash
rdm hooks .githooks && git config core.hooksPath .githooks
```

This installs the design-gate pre-commit hook: commits of implementation work
are blocked until the design documents and design review are complete and
approved (committed). Committing the design documents themselves is always
allowed — that commit *is* the approval.

## The loop, in order — for every change

1. **Classify the change.** New user journey → add a user need (V&V plan
   frontmatter) and a design input. New verifiable behavior → add a design
   input (DI-…) to the owning `kind: design` document, `traces_to` its user
   need. Changed meaning of an existing design input → edit its `text` (its
   faithfulness verdict will go stale — intended). Behavior-neutral refactor
   or docs-only → no record change; gates must stay green.
2. **Design record first, committed first.** Edit the design document(s),
   commit them **alone**. The pre-commit hook blocks implementation commits
   until this is done. Check: `rdm story design-gate --dhf <your DHF dir>`.
3. **Write the acceptance test** tagged `@allure.story("DI-…")`, against the
   design input's text (its clauses), not against the implementation.
4. **Implement** until the tagged test passes.
5. **Run the gates** exactly as your CI does:

   ```bash
   rdm story design-gate --dhf <dhf>
   pytest <tests> --alluredir=<dhf>/allure-results
   rdm story verify --dhf <dhf> --allure-results <dhf>/allure-results -o <dhf>/data/verification.yml
   rdm story faithfulness --dhf <dhf>
   rdm story release-gate --dhf <dhf> --allure-results <dhf>/allure-results
   ```

6. **Independent faithfulness review — never self-review.** Every new or
   edited tagged test needs a verdict recorded by a reviewer **independent of
   the test's author** (a human, or a separate agent instance that did not
   write the test). The reviewer decomposes the design-input text into
   clauses, proves each is covered (`rdm story mutation-probe` turns "the test
   would catch it" into executed evidence), and records:

   ```bash
   rdm story verdict DI-… --verdict faithful --reviewer <who> --rationale <per-clause reasoning>
   ```

   If you authored the test, hand this step off. A passing test proves
   something ran; the verdict is the §820.30(e) evidence it verifies the
   right thing.
7. **Re-run** `rdm story faithfulness` and `rdm story release-gate` — green.
8. Commit implementation + test (+ verdict); push; let CI re-run the gates.

## Hard rules (never)

- Never bypass the gate: no `RDM_SKIP_DESIGN_GATE=1`, no
  `git commit --no-verify`, never unset `core.hooksPath`.
- Never hand-edit faithfulness verdict files — only `rdm story verdict`.
- Never hand-edit the generated traceability matrix.
- Never record a verdict for a test you authored.
- Editing a tagged test without a fresh verdict leaves it **stale** and blocks
  release. That is the system working, not an obstacle to route around.

## Useful commands

```bash
rdm story trace UN-…|DI-… --dhf <dhf>    # the traceability slice for a need/input
rdm story mutation-probe --file <impl> --find <code> --replace <break> --test <selector>
rdm gap <checklist> <documents>          # standard-coverage gap analysis
```
