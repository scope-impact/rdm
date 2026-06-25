# RDM — Design History File

This is RDM's own DHF, produced with RDM's record-first model (it dogfoods the
method documented in `docs/record-first-architecture.md` and ADR 0001). RDM is
the product under design control here.

## Layout (the record)

```
documents/
  design_input.md                    design inputs (gated: present + complete + approved-in-git)
  design_review.md                   design review record (gated)
  verification_and_validation_plan.md user-need registry (UN-001..005) + V&V approach
  architecture.md                    system design / bounded contexts (design only, no needs)
  sdd/
    record.md         satisfies [UN-001, UN-004]
    gating.md         satisfies [UN-002, UN-003]
    verification.md   satisfies [UN-003, UN-004]
    validation.md     satisfies [UN-005]
    rendering.md      satisfies [UN-001]
```

## Model

- **User needs** (validated journeys) are defined once in the V&V plan; each
  context SDD references the user needs it contributes to via `satisfies`.
- **Verification** = acceptance criteria as `@allure`-tagged tests (RDM's own
  `tests/`), aggregated across contexts.
- **Validation** = human review + the `usability-persona` skill (formative).
- **Approval** = the reviewed, merged PR (git) — not a sign-off block here.

## Gate it

```bash
rdm story design-gate --dhf dhf      # design input + review present, complete, approved (committed)
```
