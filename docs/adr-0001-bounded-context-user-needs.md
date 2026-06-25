# ADR 0001 — User needs across bounded contexts

> Status: Proposed (design only; supersedes the earlier "product need" draft —
> that vocabulary is dropped).

## Context

We model **one SDD per bounded context** (DDD). A user need frequently spans
contexts (it is solution-independent and does not know about our context
boundaries). Earlier drafts introduced a separate "product need" layer to hold
cross-context journeys; that was redundant, because **validation is against the
user need** (21 CFR 820.30(g): "devices conform to defined user needs and
intended uses"). The journey *is* the user need. So we ditch "product need" and
keep a single need vocabulary.

## Decision

There is one need concept: the **user need**.

- A **user need** is the validated, solution-independent statement of what the
  user / intended use requires (the journey). It is the **validation** anchor.
- A user need is **cross-cutting**: **multiple SDDs may address the same user
  need** (many-to-many). It is therefore **not owned by any single SDD**.
- It is defined **once**, in the **validation & verification plan** — the
  document whose job is to plan validation, and validation is defined *against*
  user needs. Architecture stays pure design; needs sit at the validation/input
  altitude:

  ```yaml
  # documents/verification_and_validation_plan.md
  ---
  id: VVP-001
  user_needs:
    - {id: UN-001, text: "A clinician is promptly alerted to dangerous changes in a patient's vitals"}
    - {id: UN-002, text: "..."}
  ---
  ```

- Each bounded-context SDD declares the user needs its design contributes to:

  ```yaml
  # documents/sdd/alarms.md
  ---
  id: SDS-ALRM-001
  context: alarms
  satisfies: [UN-001]
  ---
  ```

- Within each contributing context, a user need is refined into **design inputs**
  (verified by `@allure`-tagged tests) and, optionally, **backlog tasks** (plan).

## Verification is anchored on design inputs (amendment)

> Status of this section: Accepted. Refines the original decision.

Validation answers "right thing?" against the **user need**; verification answers
"built right?" — and §820.30(f) defines verification as **design output meeting
design input**, not output meeting user need. So verification is anchored one
rung below the user need, on the **design input**:

- A **design input** is a verifiable requirement the design must satisfy. It is
  declared once, as structured data, in the **design-input registry**
  (`design_input.md` frontmatter), and `traces_to` the user need(s) it refines:

  ```yaml
  # documents/design_input.md
  ---
  id: DI-001
  design_inputs:
    - {id: DI-1, text: "...latency requirement...", traces_to: [UN-001]}
    - {id: DI-2, text: "...acknowledgement...",     traces_to: [UN-001]}
  ---
  ```

- Each design input is **verified** by the automated test tagged
  `@allure.story("DI-…")`. The **design-input set is the release-gate
  denominator**; a user need is verified-through when every design input that
  `traces_to` it passes, rolled up across contexts.

### Tests + Allure tags are the executable behaviour spec ("live BDD")

We keep the *value* of BDD — traceability, an executable specification, and a
living document — while dropping Gherkin's parallel DSL and step-definition glue:

- **The test IS the acceptance criterion / scenario.** There is no `.feature`
  file and no step registry to keep in sync with the code.
- **Traceability** is the `@allure.story("DI-…")` tag (required) linking the test
  to the design input it verifies; an optional `@allure.label("output", "…")`
  records the design output (component / SDD) exercised.
- **The reviewed spec** is the registries (design inputs + user needs), approved
  in git.
- **The living document** is the Allure report (and the generated traceability
  matrix), produced from executed results.
- **It stays agile**: tests evolve with the code each iteration; there is no
  separate specification artifact to maintain in lockstep.
- An `allure.step("…")` narrative is **available but optional and free-form** —
  it is **not** mandated and **need not** be Given/When/Then. A test need not
  follow any prescribed structure.

## V&V mapping

| Question | Against | Evidence |
|----------|---------|----------|
| **Validation** — "right thing?" | the **user need** (journey) | usability (IEC 62366), clinical, simulated/actual use — human + AI-persona formative, recorded as controlled docs, approved in git |
| **Verification** — "built right?" | the **design input** (§820.30(f)) | `@allure.story("DI-…")` test results; the test *is* the acceptance criterion |

A user need is **met** when it is **validated** *and* **every design input that
`traces_to` it** — across every context — is **verified**.

## ID scheme

- User need: `UN-NNN` — global, because it is cross-cutting. The **validation**
  anchor.
- Design input: `DI-n` — declared in the registry, `traces_to` user need(s). The
  **verification** anchor; tests tag it `@allure.story("DI-n")`.
- Design output: referenced by the optional `@allure.label("output", "…")`.

## Traceability (many-to-many)

| Relation | Cardinality | Source |
|----------|-------------|--------|
| user need → SDDs | N ↔ M | each SDD's `satisfies` |
| user need → design inputs | 1 ↔ N | each design input's `traces_to` |
| design input → tests | 1 ↔ N (spanning contexts) | `@allure.story("DI-…")` tags |
| design input → output | 1 ↔ N (optional) | `@allure.label("output", …)` |
| user need → validation | 1 ↔ 1+ | V&V plan + validation records |

The same user need is **referenced** by many SDDs and refined by many design
inputs, never **duplicated**: it is defined once in the registry.

## Gates (when implemented)

- **design-gate**: each SDD present, complete, approved; each `satisfies`
  resolves to a real user need; each design input `traces_to` a real user need.
- **release-gate**: every **design input** is verified (its `@allure.story("DI-…")`
  test passes, aggregated across contexts), every user need is addressed by at
  least one design input, **and** every user need has approved validation
  evidence.

## Why "product need" is dropped

- Validation is against user needs, so the cross-context journey *is* a user
  need — a separate "product need" was a redundant name for it.
- The former "context user need" was really an acceptance criterion /
  requirement (verified, not validated) — it folds into acceptance criteria.

## Consequences (implementation outline, deferred)

- `record/sdd.py`: read the user-needs registry from the V&V plan frontmatter
  (`user_needs`); glob all SDDs and read each `satisfies`; reconcile coverage.
- Templates: V&V plan frontmatter `user_needs`; per-context SDD frontmatter
  `satisfies`. The architecture document holds design only — no need listing.
- Gates + traceability matrix: aggregate verification across all SDDs per user
  need; add validation presence/approval as a separate, human-evidenced check.

## Litmus test

> Can two contexts contribute to one user need without duplicating it? Yes — the
> need is defined once in the registry; each SDD references it via `satisfies`,
> and the design inputs that `traces_to` it are verified by their tagged tests
> wherever those tests live.
