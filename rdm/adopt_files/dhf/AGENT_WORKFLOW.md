# Changing this product: the traceable loop

**Audience:** anyone — human or AI agent — about to change this codebase.
**Promise:** follow this once, top to bottom, and your change passes the
design-controls pipeline on the first try, with a complete evidence chain
behind it.

## The intent — what a "complete, traceable implementation" means

This repository is under record-first design controls. The goal of any change
is **not just working code** — it is working code plus an unbroken,
machine-checkable chain of evidence:

```
 WHY the product exists          WHAT it must do              PROOF it does it
┌─────────────────────┐      ┌──────────────────────┐      ┌──────────────────────┐
│ User need   UN-nnn  │◄─────│ Design input   DI-n  │◄─────│ Acceptance test      │
│ (V&V plan            │traces│ (owned by ONE context │verify│ @allure.story("DI-n")│
│  frontmatter)        │ _to  │  doc, kind: design)  │      │ tests/acceptance/    │
└─────────────────────┘      └──────────────────────┘      └──────────┬───────────┘
                                  approval = the git                  │ executed
                                  commit of the doc                   ▼
┌─────────────────────┐      ┌──────────────────────┐      ┌──────────────────────┐
│ Traceability matrix │◄─────│ verification.yml     │◄─────│ Allure results       │
│ (rendered, never    │      │ (generated from       │      │ (pass / fail per     │
│  hand-edited)       │      │  executed results)    │      │  tagged test)        │
└─────────────────────┘      └──────────────────────┘      └──────────────────────┘
```

Does the test actually *mean* anything? That is judged by the pull request's
reviewer — someone other than its author.

Every arrow is checked by a gate. A change is **complete** when:

1. its behavior is stated as a **design input** (`DI-n`) tracing to a **user
   need** (`UN-nnn`);
2. the design doc declaring it is **committed before the implementation** —
   the commit *is* the approval;
3. a test tagged `@allure.story("DI-n")` **passes** — executed evidence;
4. the traceability matrix regenerates cleanly;
5. the pull request is approved by a reviewer who is not its author — the
   independent check that each test genuinely verifies its requirement.

Planning artifacts (backlog tasks, issues, boards) are coordination, **never
evidence** — the record is the design docs, executed results, and git history.

## Do I need a design input?

```
Does the change alter what the product does (behavior, API, output)?
├── YES → governed by a design input
│   ├── an existing DI covers it → rdm story trace <DI-n>, continue at step 3
│   └── nothing covers it → full loop, step 1
└── NO (refactor, docs, comments, CI plumbing)
    → no DI work; commit as usual (the gates still run and should stay green)
```

## The loop

### 0 — see the landscape
```bash
rdm story new-input --dhf dhf --list     # contexts, taken DI ids, user needs
```

### 1 — the user need (WHY)
User needs live once, in `dhf/documents/verification_and_validation_plan.md`
frontmatter. Reuse an existing one; only a genuinely new validated journey gets
a new `UN-nnn` — registered **in the same change** as its first design input,
because the release gate blocks any need nothing traces to.

### 2 — declare the design input (WHAT)
```bash
rdm story new-input --dhf dhf --context <ctx> \
  --text "The system shall <clause>, <clause>, …" --traces-to UN-nnn
```
Then describe it in that context document's `## Design Inputs` /
`## Design Outputs` prose. Write the text as testable clauses — the test and
its reviewer judge it clause by clause.

### 3 — commit the design docs FIRST (the approval)
```bash
git add dhf/ && git commit -m "Approve design record: DI-n <what>"
rdm story design-gate --dhf dhf     # must print PASSED
```
The pre-commit hook blocks *implementation* commits until this passes;
committing only design docs is always allowed — that's how they get approved.

### 4 — implement (HOW)
Build what the `## Design Outputs` prose names. If the design was wrong, edit
the doc — the gate re-opens until the edit is committed. That's the feature.

### 5 — make the test real (PROOF)
Replace the scaffolded stub's failing body with real assertions against the
real code path — **one assertion per clause** — keeping the tag:
```python
@allure.story("DI-n")
@allure.label("output", "src/<impl>")
def test_<behavior>(...):
    """DI-n: <the requirement in one line>."""
```

### 6 — run the gates as CI does
```bash
rdm story design-gate --dhf dhf
pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

### 7 — commit, push, PR (the independent review)
The reviewer — never the author — checks that each tagged test fails if its
requirement is broken, not merely that it passes. To check rather than eyeball:
```bash
rdm story mutation-probe --file <impl> --find '<code for a clause>' \
  --replace '<one-line break>' --test <test_name>   # KILLED = the test catches it
```
The merged, reviewed PR completes the approval record.

## Hard rules

| Rule | Because |
|---|---|
| Never hand-edit the traceability matrix | generated from executed results |
| Never approve a pull request you authored | independent review is the verification check |
| Design docs commit before implementation | the commit is the approval; hook + CI enforce it |
| A DI is declared once; other contexts use `realises` | duplicated requirements drift |
| `dhf/allure-results/`, `dhf/data/` are generated, gitignored | evidence is produced by running |
| Planning artifacts are never cited as evidence | the record is docs + results + git |

## When a gate fails

| Failure (abridged) | Fix |
|---|---|
| design-gate: *unresolved placeholders* | finish the doc (remove TODO markers) |
| design-gate: *uncommitted changes* | commit the design docs (that commit is the approval) |
| pre-commit: *commit blocked* | fix/commit the design record first |
| release-gate: *DI-n untested* | write/tag the test, re-run the acceptance suite |
| release-gate: *DI-n failed* | fix the implementation (or the test) |
| release-gate: *user need addressed by no design input* | add a DI with `traces_to`, or remove the need |
