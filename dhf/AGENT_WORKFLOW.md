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

Every arrow but the approval is checked by a gate; the reviewer checks the
approval. A change is **complete** when:

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
the executed test results, and git history (`docs/about/plan-vs-record.md`).

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
dependencies synced automatically (session bootstrap).

### Step 0 — see the landscape

**Do:** read `dhf/documents/architecture.md` (the bounded contexts), the
`design_inputs` of the design documents under `dhf/documents/design/` (the
taken `DI` ids; the next id is one above the highest) and the `user_needs`
of `dhf/documents/verification_and_validation_plan.md`. An agent with the
`rdm` MCP server (`.mcp.json`) can `trace` a UN, DI or risk id, or `query`
the record, read-only, before changing anything.

### Step 1 — the user need (the WHY)

**Why:** validation anchors on user needs; a design input that refines no need
is unexplainable, and the release gate blocks any need no input traces to.

**Do:** most changes refine an *existing* need — reuse it. Only a genuinely new
validated journey gets a new `UN-nnn`, added to the `user_needs` frontmatter of
`dhf/documents/verification_and_validation_plan.md` **plus** a row in that
file's validation-approach table.

**Done when:** the need you'll cite is in the V&V plan's `user_needs`.

### Step 2 — declare the design input (the WHAT)

**Why:** the design input is the verifiable requirement, the acceptance
criterion the test will be judged against as a whole. Write each thing it
requires so a test can check it; vague inputs produce unreviewable tests. What
must be accepted on its own is a separate design input: write one `shall`, and
never join with "and" two behaviours that could be accepted apart — a joined
text invites a test of one half (Design Review 59).

**Do:** add the next `DI-n` to the owning context's `design_inputs`
frontmatter, with its `text` and `traces_to`, and describe the intended
output in that document's `## Design Outputs`, naming the input by id. The
frontmatter is the input's only statement: the body never restates it. A context that helps realise an input owned elsewhere lists it under
`realises` — an input is declared once, never duplicated.

**Done when:** `uv run rdm story trace DI-n` shows your input, its need, and
its owning context.

### Step 3 — commit the design docs FIRST (the approval)

**Why:** §820.30 requires design review before implementation; here the
reviewed, committed doc *is* the approval. No hook enforces the order any
more (Design Review 72): the pull-request reviewer reads the commits in
order, so commit the design record before the code it governs.

**Do:**
```bash
git add dhf/ && git commit -m "Approve design record: DI-n <what>"
```

### Step 4 — implement (the HOW)

**Do:** build what the `## Design Outputs` prose names. If you discover the
design was wrong, edit the design doc in its own commit: the record stays
true.

### Step 5 — make the test real (the PROOF)

**Why:** the design input is the acceptance criterion and the tagged test
verifies it ("live BDD") — there is no separate spec to drift out of date. The
test's verification steps are its own: one for each thing the design input
requires, but never criteria of their own (a part that must be accepted
separately is a separate design input). Until a passing test exists, the
release gate stays honestly red: the input is untested.

**Do:** write the test in `tests/acceptance/`, with real assertions against
the real code path — **one verification step for each thing the DI text
requires** — tagged with the input:

```python
@allure.story("DI-n")                      # the link the whole chain hangs on
@allure.label("output", "<source file>")  # optional: the code it exercises
def test_<behavior>(...):
    """DI-n: <the requirement in one line>."""
    with verification_step("<what this step checks>"):   # tests/acceptance/evidence.py
        result = ...
        attach("<what was checked>", result)       # kept in the Allure results
        assert ...
```

A named verification step makes a failure say what it checked; an attachment
keeps what the assertion looked at. The steps belong to the test, never to the
criterion. Declare only the story: `rdm.pytest_plugin` (hooked in
`tests/acceptance/conftest.py`) labels every run with the commit under test
and records who ran it and where. This is for acceptance
(end-to-end) tests only: unit tests carry no Allure tags, steps or
attachments. Both land in the Allure results, which the release
bundle retains and the graph projects (`trace` lists them).

**Changing an existing test:** before adding a step to a test that already
verifies a design input, read that input's text. If the text does not state
what the new step checks, the step does not belong there yet: amend the text,
or declare a new design input with its own test, in the same design review
entry as the fix. Never let a test grow past its text — that is how the
design gate's test (DI-2, removed in Design Review 72) reached fourteen steps under a text that named none of them (Design
Review 59).

**Done when:** `uv run pytest tests/acceptance -q` passes, and each step
checks something the input's text states.

### Step 6 — run the gates as CI will

```bash
uv run pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```
Plus the general suite: `uv run pytest tests`, `uv run ruff check .`, and
`uv run --extra docs mkdocs build --strict` if docs changed.

**Done when:** the release gate prints `PASSED` (the matrix can then be rendered from
the generated data: `uv run rdm render dhf/documents/traceability_matrix.md
dhf/config.yml dhf/data/verification.yml` — generated output, never hand-edited).

### Step 7 — commit, push, PR (the independent review)

Ordinary git from here. The pull request **is** the independent verification:
a reviewer other than the author reads each DI's text against its tagged test
and asks whether the test would fail if the behavior broke — a tautology, a
mocked-out code path, or a test that checks two of the three things the DI
requires is a reason to request changes. To check rather than eyeball, break
one of them by hand and run its test: it must fail.

The reviewer says, in the review, that each changed design input's text was
read beside its test (the pull-request template asks). Keep a fix pull request
to one bounded context: no reviewer reads every input against its test inside
a change that spans them all.

The merged, reviewed PR completes the approval record.

**Merge with a merge commit — never squash or rebase.** A squash folds the
record-first commits (design document, then implementation) into one, so the
history no longer shows the design was approved before the code; a rebase
rewrites the commits that were reviewed. Configure the repository to allow
merge commits only, as `examples/github-document-control/` does
(`allow_squash_merge: false`, `allow_rebase_merge: false`).

## Before a release — the chain review

No gate judges whether a test proves its design input; a person does. Before
each release, read every design input with its whole chain — its user needs,
its design prose, its tagged test and that test's verification steps, and
its last run — and record what was found and decided as a
design review, one input at a time (Design Review 58 is the first). Changes it
asks for go through this loop like any other.

## Hard rules

| Rule | Because |
|---|---|
| Never hand-edit the traceability matrix | it is generated from executed results; edits would be fiction |
| Never approve a PR you authored | self-review over-rates; independence is the entire point of the PR review |
| Design docs commit before implementation | the commit is the approval; the reviewer reads the order |
| Allure tags, steps and attachments go in acceptance tests only | unit tests are not evidence of record; `tests/allure_scope_test.py` holds the line |
| Merge PRs with a merge commit, never squash or rebase | a squash erases the design-before-code order the record-first commits prove |
| A DI is declared once; other contexts use `realises` | duplicated requirements drift apart |
| A test checks only what its DI's text states | a step the text does not state is a requirement nobody approved |
| One bounded context per fix PR | a reviewer cannot read every input against its test in one diff |
| A chain review, recorded as a design review, before each release | no gate judges whether a test proves its input |
| `dhf/allure-results/`, `dhf/data/` are generated, gitignored | evidence is produced by running, not by committing |
| Backlog/issues/plans are never cited as evidence | plan-vs-record boundary; the record is SDD + Allure + git |
| Backlog task files change only via the `backlog` CLI | see `AGENTS.md` |

## When a gate fails

| Failure message (abridged) | It means | Fix |
|---|---|---|
| release-gate: *DI-n untested* | no executed result for the tag | write/tag the test, re-run the acceptance suite |
| release-gate: *DI-n failed* | the tagged test failed | fix the implementation (or the test) |
| release-gate: *user need addressed by no design input* | a UN nothing traces to | add a DI with `traces_to`, or remove the need |
| release-gate: *no risk_policy is declared* | the register has risks but no acceptability criteria | declare a `risk_policy` (see `docs/use/risk-register.md`); an agent's draft is `status: proposed` |
| release-gate: *risk … residual not evaluated* | a controlling design input has no passing test | make that DI's tagged test pass |
| release-gate: *risk … needs an acceptance* / *unacceptable residual* | the policy does not accept the residual as it stands | add or strengthen a control (a DI), or — where the policy says `justify` — record who accepted it and why |
| release-gate: *risk is proposed* (warning) | no person has approved that rating | a maintainer reviews it and sets `status: approved` |
| *orphan tag* (warning) | `@allure.story` id matches no declared DI | declare the DI or fix the tag |
