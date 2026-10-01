# Changing RDM: the traceable loop

**Audience:** anyone — human or AI agent — about to change RDM.
**Promise:** follow this once, top to bottom, and your change passes CI's
design-controls pipeline on the first try, with a complete evidence chain
behind it.

## The intent — what a "complete, traceable implementation" means

RDM is a design-controls tool for medical-device software, and it governs its
own development with the same controls. The goal of any change here is **not
just working code** — it is working code plus an unbroken, machine-checkable
chain of evidence:

```
 WHY the product exists          WHAT it must do              PROOF it does it
┌─────────────────────┐      ┌──────────────────────┐      ┌──────────────────────┐
│ User need   UN-nnn  │◄─────│ Design input   DI-n  │◄─────│ Acceptance test      │
│ (V&V plan            │traces│ (owned by ONE context │verify│ @allure.story("DI-n")│
│  frontmatter)        │ _to  │  doc, kind: design)  │      │ tests/acceptance/    │
└─────────────────────┘      └──────────────────────┘      └──────────┬───────────┘
                                  approval = the git                  │ executed
                                  commit of the doc                   │ results
                                                                      ▼
┌─────────────────────┐      ┌──────────────────────┐      ┌──────────────────────┐
│ Traceability matrix │◄─────│ verification.yml     │◄─────│ Allure results       │
│ (rendered, never    │      │ (generated from       │      │ (one per tagged-test │
│  hand-edited)       │      │  executed results)    │      │  run, gitignored)    │
└─────────────────────┘      └──────────────────────┘      └──────────────────────┘
```

Whether the test actually *means* anything is judged by a human: the change
lands through a pull request that a reviewer other than the author approves.

Every arrow is checked by a gate. A change is **complete** when:

1. its behavior is stated as a **design input** (`DI-n`) tracing to a **user
   need** (`UN-nnn`) — so anyone can ask *why does this code exist?* and get an
   answer;
2. the design doc declaring it is **committed before the implementation** —
   the commit *is* the approval; there is no separate sign-off bureaucracy;
3. a test tagged `@allure.story("DI-n")` **passes** — executed evidence, not a
   claim;
4. the change is merged through a **pull request approved by a reviewer other
   than the author**, who judges whether the test actually verifies the
   requirement — because a test can pass without proving anything, and an
   agent grading its own homework reliably over-grades;
5. the traceability matrix regenerates cleanly — it is derived from the record,
   so it can never drift from reality.

Skip a link and CI fails — not as punishment, but because a missing link means
someone later (an auditor, a reviewer, another agent) can no longer walk the
chain. This document exists so you build the chain *as you go* instead of
reverse-engineering it under a red build.

One boundary to keep in mind throughout: **Backlog tasks, GitHub issues, and
plans are coordination, never evidence.** The record is only the design docs,
the executed test results, and git history (`docs/plan-vs-record.md`).

## Do I need a design input?

```
Does the change alter what RDM does (behavior, CLI, output, gate logic)?
├── YES → it is governed by a design input
│   ├── an existing DI already covers it
│   │     → find it: uv run rdm story trace <DI-n | UN-nnn>
│   │     → skip to step 3 (edit its design doc prose if the "how" changed,
│   │       then implementation → test → PR review)
│   └── nothing covers it → full loop, step 1
└── NO (refactor, docs, comments, CI plumbing)
    → no DI work; commit as usual (the gates still run and should stay green)
```

## The loop

Each step says **why** it exists, **do** exactly what, and **done when** you
can verify it. The commands assume the repo root; agent sessions have
dependencies synced and the local gate active automatically (session bootstrap).

### Step 0 — see the landscape

**Do:**
```bash
uv run rdm story new-input --dhf dhf --list
```
This prints the bounded contexts, every taken `DI` id, the next free id, and
the registered user needs — the vocabulary you need for every step below.
An agent with the `rdm` MCP server (`.mcp.json`) can also `trace` a UN, DI
or risk id, or `query` the record, read-only, before changing anything.

### Step 1 — the user need (the WHY)

**Why:** validation anchors on user needs; a design input that refines no need
is unexplainable, and the release gate blocks any need no input traces to.

**Do:** most changes refine an *existing* need — reuse it. Only a genuinely new
validated journey gets a new `UN-nnn`, added to the `user_needs` frontmatter of
`dhf/documents/verification_and_validation_plan.md` **plus** a row in that
file's validation-approach table.

**Done when:** the need you'll cite appears in `--list` output above.

### Step 2 — declare the design input (the WHAT)

**Why:** the design input is the verifiable requirement — the sentence the test
will be judged against, clause by clause. Write it as testable clauses; vague
inputs produce unreviewable tests.

**Do:**
```bash
uv run rdm story new-input --dhf dhf \
  --context <ctx> --text "RDM shall <clause>, <clause>, …" --traces-to UN-nnn
```
This allocates the next `DI-n`, inserts it into that context's `design_inputs`
frontmatter, writes a stub tagged test (it *fails on purpose* — see step 5),
and prints your remaining checklist. Then, by hand, describe the input and the
intended output in that document's `## Design Inputs` / `## Design Outputs`
prose. A context that helps realise an input owned elsewhere lists it under
`realises` — an input is declared once, never duplicated.

**Done when:** `uv run rdm story trace DI-n` shows your input, its need, and
its owning context.

### Step 3 — commit the design docs FIRST (the approval)

**Why:** §820.30 requires design review before implementation; here the
reviewed, committed doc *is* the approval. The pre-commit hook enforces the
order: implementation commits are blocked while the design record is
incomplete or uncommitted — but committing *only* design docs is always
allowed (that's how they become approved).

**Do:**
```bash
git add dhf/ && git commit -m "Approve design record: DI-n <what>"
```

**Done when:** `uv run rdm story design-gate --dhf dhf` prints `PASSED`.

### Step 4 — implement (the HOW)

**Do:** build what the `## Design Outputs` prose names. If you discover the
design was wrong, edit the design doc — the gate re-opens until the edit is
committed. That friction is the feature: the record stays true.

### Step 5 — make the test real (the PROOF)

**Why:** the tagged test *is* the acceptance criterion ("live BDD") — there is
no separate spec to drift out of date. The scaffolded stub fails on purpose so
the release gate stays honestly red until real proof exists; a stub that
passed would be a lie the pipeline could not see.

**Do:** replace the stub body in `tests/acceptance/` with real assertions
against the real code path — **one assertion per clause of the DI text** —
keeping the tag and labelling the output:

```python
@allure.story("DI-n")                      # the link the whole chain hangs on
@allure.label("output", "rdm/<impl>.py")   # which design output this exercises
def test_<behavior>(...):
    """DI-n: <the requirement in one line>."""
```

**Done when:** `uv run pytest tests/acceptance -q` passes.

### Step 6 — run the gates as CI will

```bash
uv run rdm story design-gate --dhf dhf
uv run pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```
Plus the general suite: `uv run pytest tests`, `uv run ruff check .`, and
`uv run --extra docs mkdocs build --strict` if docs changed.

**Done when:** all four print `PASSED` (the matrix can then be rendered from
the generated data: `uv run rdm render dhf/documents/traceability_matrix.md
dhf/config.yml dhf/data/verification.yml` — generated output, never hand-edited).

### Step 7 — commit, push, PR (the independent review)

Ordinary git from here. The pull request **is** the independent verification:
a reviewer other than the author reads each DI's text against its tagged test
and asks whether the test would fail if the behavior broke — a tautology, a
mocked-out code path, or 2 of 3 clauses covered is a reason to request
changes. To check rather than eyeball, break a clause and run its test:

```bash
uv run rdm story mutation-probe --file <impl> --find '<code for a clause>' \
  --replace '<one-line break>' --test <test_name>   # KILLED = the test catches it
```

The merged, reviewed PR completes the approval record.

## A worked example — from this repository's own history

`rdm story new-input` itself was added exactly this way; every artifact is in
the repo to inspect:

| Step | What happened | Where to look |
|---|---|---|
| 1 | UN-010 registered ("a contributor is guided to author a fully traced design input") | `verification_and_validation_plan.md` frontmatter |
| 2 | DI-22 declared in the scaffolding context, 6-clause requirement text | `dhf/documents/design/scaffolding.md` |
| 3 | Design docs committed *before* any code | commit `Approve design record: UN-010, DI-22, …` |
| 4–5 | Implementation + tagged test | `rdm/gates/new_input.py`, `tests/acceptance/test_scaffolding.py` |
| 6–7 | All gates green, pushed, PR reviewed | CI run on the PR |

History: at the time, an independent review step (the since-retired
faithfulness gate, Design Review 4) found that the test passed with a
one-context fixture even though a mutant ignoring `--context` survived; the
author strengthened the fixture to two contexts (commit `Strengthen the DI-22
test …`). That kind of gap — a passing test that does not prove a clause — is
now the PR reviewer's to catch.

## Hard rules

| Rule | Because |
|---|---|
| Never hand-edit the traceability matrix | it is generated from executed results; edits would be fiction |
| Never approve a PR you authored | self-review over-rates; independence is the entire point of the PR review |
| Design docs commit before implementation | the commit is the approval; the hook and CI enforce the order |
| A DI is declared once; other contexts use `realises` | duplicated requirements drift apart |
| `dhf/allure-results/`, `dhf/data/` are generated, gitignored | evidence is produced by running, not by committing |
| Backlog/issues/plans are never cited as evidence | plan-vs-record boundary; the record is SDD + Allure + git |
| Backlog task files change only via the `backlog` CLI | see `AGENTS.md` |
| `RDM_SKIP_DESIGN_GATE=1` is for emergencies, and CI still runs everything | the bypass is loud and local-only |

## When a gate fails

| Failure message (abridged) | It means | Fix |
|---|---|---|
| design-gate: *unresolved placeholders* | `TODO`/`ENDTODO` left in a design doc | finish the doc |
| design-gate: *uncommitted changes* | a design doc is edited but not committed | commit it (that commit is the approval) |
| pre-commit: *commit blocked* | implementation staged while the design gate fails | fix/commit the design record first |
| pre-commit: *'rdm' not on PATH … blocked* | gate runner missing | `uv sync --all-extras` (or `pip install rdm`) |
| release-gate: *DI-n untested* | no executed result for the tag | write/tag the test, re-run the acceptance suite |
| release-gate: *DI-n failed* | the tagged test failed | fix the implementation (or the test) |
| release-gate: *user need addressed by no design input* | a UN nothing traces to | add a DI with `traces_to`, or remove the need |
| release-gate: *no risk_policy is declared* | the register has risks but no acceptability criteria | declare a `risk_policy` (see `docs/risk.md`); an agent's draft is `status: proposed` |
| release-gate: *risk … residual not evaluated* | a controlling design input has no passing test | make that DI's tagged test pass |
| release-gate: *risk … needs an acceptance* / *unacceptable residual* | the policy does not accept the residual as it stands | add or strengthen a control (a DI), or — where the policy says `justify` — record who accepted it and why |
| release-gate: *risk is proposed* (warning) | no person has approved that rating | a maintainer reviews it and sets `status: approved` |
| *orphan tag* (warning) | `@allure.story` id matches no declared DI | declare the DI or fix the tag |
