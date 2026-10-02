# RDM — Design History File

This is RDM's own DHF, produced with RDM's record-first model (it dogfoods the
method documented in `docs/record-first-architecture.md` and ADR 0001). RDM is
the product under design control here. The step-by-step procedure for changing
RDM inside this DHF's scope is `AGENT_WORKFLOW.md` (this directory).

Scope note: RDM is not a medical device, so this DHF deliberately implements
the **design-controls slice** (design inputs, review, verification, validation,
traceability — the §820.30-shaped record) plus a register of RDM's own tool
risks, and not the full IEC 62304 lifecycle document set (a complete risk
management file, SOUP register, maintenance and problem-resolution plans, …). Running `rdm gap 62304_2015_class_b` over these
documents is expected to report those items as missing.

## Layout (the record)

```
AGENT_WORKFLOW.md                    the change procedure (start here)
documents/
  design_review.md                   design review record (gated)
  verification_and_validation_plan.md user-need registry (UN-…) + V&V approach
  document_control.md                git/GitHub as this record's document control (Part 11-mapped)
  architecture.md                    system design / bounded contexts (design only, no needs)
  traceability_matrix.md             matrix TEMPLATE (rendered from generated data; never hand-edited)
  design/                            one `kind: design` doc per bounded context:
    <context>.md                       the design inputs it owns (what) + design output (how)
  risk/
    policy.md                          risk acceptability policy (RMP-001, proposed)
    tool_risks.md                      `kind: risk` register of RDM's tool risks (RMF-001, proposed)
```

The live inventory — which contexts exist, which design inputs each owns, and
which user needs they trace to — is generated, not maintained here:

```bash
rdm story new-input --dhf dhf --list    # contexts, taken DI ids, user needs
rdm story trace UN-… | DI-…             # one need's / input's slice
```

## Model

- **User needs** (validated journeys) are defined once in the V&V plan; each
  context serves the needs its design inputs trace to (derived, never declared).
- **Design inputs** (the verifiable *what*) are declared once, in the per-context
  design document that owns them (`design_inputs` frontmatter), and `traces_to`
  the user need they refine. A context can `realises` an input owned elsewhere.
- **Verification** = each design input (an acceptance criterion: a `shall`
  requirement, baseline or risk-based) is verified by its
  `@allure.story("DI-…")` test ("live BDD"), aggregated across contexts. The
  test's verification steps belong to the test, not to the criterion. Vocabulary: `CONTEXT.md`.
- **Independent verification** = the human-reviewed pull request: a reviewer
  other than the author judges whether each tagged test actually verifies its
  input. Git is the controlled record of that review.
- **Validation** = human review + the `usability-persona` skill (formative), UN-keyed.
- **Approval** = the reviewed, merged PR (git) — not a sign-off block here.

## Gate it

```bash
rdm story design-gate --dhf dhf      # design doc(s) + review present, complete, approved (committed)
```

## Verify it (generate the traceability matrix)

```bash
uv run pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results   # run the tagged ACs
rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
rdm story release-gate --dhf dhf --allure-results dhf/allure-results   # PASS when all verified + every need addressed
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

`dhf/allure-results/` and `dhf/data/verification.yml` are generated (gitignored);
they are produced by running the acceptance suite, not committed.

