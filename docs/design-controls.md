# Design controls and traceability — the `rdm story` commands

This is the user manual for the record-first workflow. The concepts are in
[Record-first architecture](record-first-architecture.md); the step-by-step
change procedure is the [agent workflow](agent-workflow.md). Requires
`pip install "rdm[story-audit]"`.

## The model in one breath

User needs (`UN-nnn`, in the V&V plan frontmatter) are refined by design
inputs (`DI-n`, declared in per-context `kind: design` documents). Each design
input is verified by a test tagged `@allure.story("DI-n")` — the test *is* the
acceptance criterion — and independently confirmed **faithful** (the test
really verifies the requirement). Approval is the git commit. The traceability
matrix is generated, never hand-edited.

## Declaring work

```bash
rdm story new-input --dhf dhf --list        # contexts, taken DI ids, next free id, user needs
rdm story new-input --dhf dhf --context alarms \
  --text "The system shall …" --traces-to UN-001
```

Scaffolds a traced design input: allocates the next unused `DI-n`, inserts the
frontmatter entry into the owning context's document, writes a stub tagged
test that **fails until implemented**, and prints the remaining checklist.
Unknown contexts and user needs are rejected.

## The gates

```bash
rdm story design-gate --dhf dhf
```
The design documents and design review must be **present, complete (no
`TODO`/`ENDTODO` markers), and approved (committed clean)**. Editing an
approved document re-opens the gate. Install the matching pre-commit hook with
`rdm hooks .githooks && git config core.hooksPath .githooks` (or let
[`rdm adopt`](quickstart-existing-repo.md) set it up): implementation commits
are blocked while the gate is red; committing only design docs is always
allowed — that commit is the approval.

```bash
pytest tests/acceptance --alluredir=dhf/allure-results
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
```
Runs the acceptance criteria and reconciles the executed Allure results
against every declared design input: verified / failed / untested per input,
written to the data file the traceability matrix renders from.

```bash
rdm story faithfulness --dhf dhf
```
Reports whether each design input has a **current, independent** verdict that
its test actually verifies it. Verdicts are hash-pinned to the test source —
editing a tagged test makes its verdict `stale` until re-reviewed.

```bash
rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```
The hard gate: design approved **and** every design input verified by a
passing test **and** faithful **and** every user need addressed by at least
one input.

## Polyglot products

Executed verification (Allure results) is language-agnostic, and source-tag
discovery also reads JS/TS `allure.story(...)` calls and Java
`@Story(...)`/`@Feature(...)` annotations across conventional test-file names
(`*.test.ts`, `*.spec.js`, `*Test.java`, `*_test.go`, …). Function-scope
verdict hashing is Python-only; tests in other languages pin at whole-file
scope.

## Faithfulness review

A passing test proves code ran, not that the requirement is met. Whoever wrote
the test must not review it. The reviewer decomposes the requirement into
clauses and proves coverage with executed mutations:

```bash
rdm story mutation-probe --file rdm/gaps.py \
  --find 'return 3' --replace 'return 0' \
  --test test_reports_missing_checklist_references
# KILLED = the test caught the break; SURVIVED = the clause is not covered.
# The probed file is always restored.

rdm story verdict DI-10 --dhf dhf --verdict faithful \
  --reviewer "reviewer-name (independent of author)" \
  --reviewed-tests test_reports_missing_checklist_references \
  --rationale "clauses: … (each with the mutation that was KILLED)"
```

Verdicts: `faithful` passes; `partial` (with `--uncovered`), `unfaithful`,
`weak`, `stale`, and missing all block release.

**Replayable reviews.** Record the probes with the verdict (repeatable
`--probe`, JSON with `file`/`find`/`replace`/`test`), and the review becomes
continuously verifiable instead of trust-at-review-time:

```bash
rdm story verdict DI-10 … \
  --probe '{"file": "rdm/gaps.py", "find": "return 3", "replace": "return 0", "test": "test_reports_missing"}'
rdm story faithfulness --dhf dhf --replay   # re-executes every recorded killing
                                            # probe; fails if any now survives
rdm story faithfulness --dhf dhf --stale    # only the non-faithful worklist
```

**Hash scope.** A verdict pins what the reviewer saw. The default `module`
scope covers the full test file(s), so editing a shared helper or fixture
re-opens the review too; `--hash-scope function` pins only the tagged
functions (for files with unrelated churn). Verdicts recorded before scopes
existed are honored as function-scoped.

## Querying and reporting

```bash
rdm story trace DI-4 --dhf dhf          # one input: its need, owner, tests, status
rdm story trace UN-004 --dhf dhf        # one need: the inputs that refine it
rdm story audit .                       # repo-wide traceability report + score
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

## Release artifacts

```bash
rdm story dmr documents/ -o data/dmr.yml
```
Generates device-master-record index data (one entry per controlled document:
id, title, path, revision) from the documents' own frontmatter — the index
stays a record, never a hand-maintained table.

```bash
rdm story evidence-bundle --dhf dhf --allure-results dhf/allure-results -o release-evidence/
```
Writes the retained release evidence set — verification data, the rendered
traceability matrix, the faithfulness verdicts, and a manifest — ready to
attach to a release tag so the evidence outlives CI artifact retention.

`rdm story audit` is DHF-aware: on a record-first repository it reports each
design input's test-tag coverage and counts untagged inputs against the score.

## Validation evidence (formative)

```bash
rdm story persona --vv-plan dhf/documents/verification_and_validation_plan.md \
  --persona-results persona-results/
```

Reconciles AI-persona simulated-use runs against the user-need registry —
formative usability evidence only; it informs but never gates release.

**Summative records.** Human validation judgments live in the record too:
`<dhf>/validation/UN-…-validation.json` (`user_need`, `disposition:
"approved"`, `reviewer`, `summary`). The release gate names every user need
lacking an approved record — as a warning, because a machine cannot supply
the judgment, but its absence must never be silent.

## Planning-side tools

`rdm story sync`, `check-ids`, `backlog-validate`, and `rdm pm sync` operate on
the optional planning layer (Backlog.md / GitHub Issues / DuckDB analytics).
Planning artifacts are coordination, never the record — see
[Plan vs. record](plan-vs-record.md).
