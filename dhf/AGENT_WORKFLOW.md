# Agent workflow — how to change RDM traceably

This is the canonical, end-to-end procedure for changing RDM. It applies to
**every** contributor, human or AI agent. RDM develops itself under its own
record-first design controls (see `dhf/README.md` for the model and
`docs/plan-vs-record.md` for the plan/record boundary); this runbook is the
*procedure* those documents imply, in execution order. CI
(`.github/workflows/design-controls.yml`) and the pre-commit hook enforce it —
following this document is how you pass them on the first try.

## First: does your change need a design input?

```
Does the change alter what RDM does (behavior, CLI, output, gate logic)?
├── YES → it is governed by a design input (DI-n)
│   ├── an existing DI already covers it → step 4 onward
│   │   (find it: uv run rdm story trace <DI-n | UN-nnn>)
│   └── no DI covers it → full loop, step 1 onward
└── NO (refactor, docs, comments, CI plumbing)
    → no DI work, but the gates still run on your commit/PR (step 7)
```

## The traceable loop

### 1. Register or confirm the user need

User needs (`UN-nnn`) live **once**, in the frontmatter of
`dhf/documents/verification_and_validation_plan.md` (`user_needs`). Most changes
refine an existing need — reuse it. Only a genuinely new validated journey gets
a new UN, and every UN must end up addressed by at least one design input or the
release gate blocks. A new UN also needs a row in the V&V plan's validation
approach table.

### 2. Declare the design input in its owning context

One bounded context **owns** each design input, in its `kind: design` document
under `dhf/documents/design/` (discovery keys on the `kind: design` frontmatter,
never on the filename). Scaffold it:

```bash
uv run rdm story new-input --dhf dhf --list             # contexts, taken ids, next free id, user needs
uv run rdm story new-input --dhf dhf \
  --context <ctx> --text "RDM shall …" --traces-to UN-nnn
```

This allocates the next unused `DI-n`, inserts the entry into that context's
`design_inputs` frontmatter, writes a stub tagged test (see step 5), and prints
the remaining checklist. Then, by hand:

- add the DI to the `## Design Inputs` / `## Design Outputs` prose of the same
  document;
- if another context helps realise it, list the id under that context's
  `realises` (an input is declared **once**, never duplicated);
- write the `text` as verifiable clauses — the faithfulness review (step 6)
  will decompose it clause by clause, and every clause must be covered by an
  assertion.

### 3. Commit the design docs FIRST — the commit is the approval

There is no sign-off block: approval **is** the reviewed commit/merge in git.
The design gate fails on placeholder text (`TODO`/`ENDTODO`) and on uncommitted
design docs, and the pre-commit hook blocks *implementation* commits until the
design record is approved (committing only design docs is always allowed —
that's how they get approved). Check before you implement:

```bash
uv run rdm story design-gate --dhf dhf
```

### 4. Implement the design output

Write the implementation the `## Design Outputs` section names. Editing an
approved design doc later re-opens the gate — that is intended, not a bug.

### 5. Write the acceptance test — the test IS the acceptance criterion

Each DI is verified by a test in `tests/acceptance/` ("live BDD" — no Gherkin,
no separate spec):

```python
@allure.story("DI-n")                      # required: links test → design input
@allure.label("output", "rdm/<impl>.py")   # names the design output exercised
def test_<what_it_verifies>(...):
    """DI-n: <the requirement, in one line>."""
```

Assert against the **real** code path, one assertion per requirement clause.
If `new-input` scaffolded a stub for you, replace its `pytest.fail(...)` body —
the stub fails by design so the release gate stays honestly red until you do.

### 6. Record the faithfulness verdict — independently

A passing test proves something ran, not that it verifies the requirement. Every
DI needs a current, **independent** verdict (§820.30(e)): whoever wrote the test
must not review it — hand this to the `test-faithfulness` skill, a second agent,
or a human. The reviewer decomposes the DI text into clauses, proves coverage
with mutation probes, and records:

```bash
uv run rdm story mutation-probe --file <impl.py> \
  --find '<code implementing a clause>' --replace '<one-line break>' \
  --test <the tagged test>        # KILLED = clause covered; always restores the file
uv run rdm story verdict DI-n --dhf dhf --verdict faithful \
  --reviewer "<who> (independent of author)" \
  --reviewed-tests <test names> \
  --rationale "clauses: …(✓ mutation killed), …(✓ …)"
```

Verdicts are hash-pinned to the DI text + verifying-test source. **Editing a
tagged test makes its verdict `stale`** and CI fails until re-reviewed — an edit
re-opens the review, deliberately. Only `faithful` passes; `partial`,
`unfaithful`, `weak`, `stale`, and missing all block release.

### 7. Run the gates exactly as CI does

```bash
uv run rdm story design-gate --dhf dhf
uv run pytest tests/acceptance --alluredir=dhf/allure-results
uv run rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
uv run rdm story faithfulness --dhf dhf
uv run rdm story release-gate --dhf dhf --allure-results dhf/allure-results
```

Plus the general checks: `uv run pytest tests`, `uv run ruff check .`, and
`uv run --extra docs mkdocs build --strict` if you touched docs.

### 8. Regenerate the traceability matrix — never hand-edit it

```bash
uv run rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

## Hard rules

| Rule | Why / enforced by |
|---|---|
| Never hand-edit `dhf/documents/traceability_matrix.md` content | it is generated from executed results (recipe in the file itself) |
| Editing a tagged test → its verdict goes `stale` → re-review | hash-pin; `rdm story faithfulness` / CI fail until re-recorded |
| Never review a test you authored | independence is the point of the faithfulness verdict |
| `dhf/allure-results/` and `dhf/data/verification.yml` are generated, gitignored | produced by running the suite, not committed |
| A DI is declared once (owning context); others use `realises` | declare-once model, ADR 0001 |
| Backlog.md tasks / GitHub issues are planning, **never** evidence | plan-vs-record boundary (`docs/plan-vs-record.md`); the record is SDD + Allure + git |
| Backlog task files are edited only via the `backlog` CLI | see `AGENTS.md` |
| `RDM_SKIP_DESIGN_GATE=1` exists for emergencies, not convenience | the bypass is logged; CI still runs the full pipeline |

## When a gate fails

| Failure | Meaning | Fix |
|---|---|---|
| design-gate: *placeholder text* | `TODO`/`ENDTODO` left in a design doc | finish the doc |
| design-gate: *uncommitted changes* | design doc edited but not committed | commit the design docs (that commit is the approval) |
| pre-commit: *commit blocked* | implementation staged while design gate fails | approve (commit) the design docs first |
| release-gate: *DI-n untested* | no `@allure.story("DI-n")` result found | write/tag the acceptance test, re-run the suite |
| release-gate: *DI-n failed* | the tagged test failed | fix the implementation (or the test, then step 6 again) |
| faithfulness: *unreviewed* | no verdict recorded for the DI | independent review → `rdm story verdict` |
| faithfulness: *stale* | test or DI text changed since the verdict | re-run the independent review, re-record |
| faithfulness: *partial / unfaithful / weak* | the test does not cover every clause | strengthen the test, then re-review |
| release-gate: *user need addressed by no design input* | UN declared but nothing traces to it | add a DI with `traces_to`, or remove the need |
| verify/trace: *orphan tag* (warning) | `@allure.story` id matches no declared DI | declare the DI or fix the tag |
