# RDM — Design History File

This is RDM's own DHF, produced with RDM's record-first model (it dogfoods the
method documented in `docs/record-first-architecture.md` and ADR 0001). RDM is
the product under design control here.

## Layout (the record)

```
documents/
  design_review.md                   design review record (gated)
  verification_and_validation_plan.md user-need registry (UN-001..005) + V&V approach
  architecture.md                    system design / bounded contexts (design only, no needs)
  design/                            one `kind: design` doc per context: design inputs (what) + output (how)
    record.md         satisfies [UN-001, UN-004]   owns DI-1, DI-6
    gating.md         satisfies [UN-002, UN-003]   owns DI-2, DI-3
    verification.md   satisfies [UN-003, UN-004]   owns DI-4
    validation.md     satisfies [UN-005]           owns DI-5
    rendering.md      satisfies [UN-001]           realises DI-1, DI-4
```

## Model

- **User needs** (validated journeys) are defined once in the V&V plan; each
  context design doc references the needs it contributes to via `satisfies`.
- **Design inputs** (the verifiable *what*) are declared once, in the per-context
  design document that owns them (`design_inputs` frontmatter), and `traces_to`
  the user need they refine. A context can `realises` an input owned elsewhere.
- **Verification** = each design input's `@allure.story("DI-…")` test (the test
  *is* the acceptance criterion, "live BDD"), aggregated across contexts.
- **Validation** = human review + the `usability-persona` skill (formative), UN-keyed.
- **Approval** = the reviewed, merged PR (git) — not a sign-off block here.

## Gate it

```bash
rdm story design-gate --dhf dhf      # design doc(s) + review present, complete, approved (committed)
```

## Verify it (generate the traceability matrix)

```bash
uv run pytest tests/acceptance --alluredir=dhf/allure-results   # run the tagged ACs
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm story release-gate --dhf dhf --allure-results dhf/allure-results   # PASS when all needs verified
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

`dhf/allure-results/` and `dhf/data/verification.yml` are generated (gitignored);
they are produced by running the acceptance suite, not committed.

