# Worked example — VitalView user needs across bounded contexts

Applies ADR 0001 to **VitalView**, a clinician-facing remote patient monitoring
dashboard (SaMD, web app). A software app is used deliberately: it has a **user
interface** an AI persona can drive for formative usability evidence, which a
firmware device does not. See `examples/vitalview-samd/`.

## User needs (validated, cross-cutting)

Defined once in the V&V plan frontmatter
(`verification_and_validation_plan.md`) — not in the architecture document,
which holds design only:

| ID | User need (the journey) |
|----|-------------------------|
| `UN-001` | A clinician is promptly alerted to a patient's dangerous vital-sign changes and can acknowledge the alert. |
| `UN-002` | A clinician can securely sign in and view a patient's current and recent vital signs. |
| `UN-003` | Access to patient data is restricted to authorized clinicians and is audited. |

## Bounded contexts and what they satisfy

Each context SDD declares `satisfies: [UN-…]`. A user need is **referenced** by
multiple SDDs, never duplicated.

| Context | Design document | `satisfies` |
|---------|-----------------|-------------|
| `auth` | `design/auth.md` | UN-002, UN-003 |
| `ingestion` | `design/ingestion.md` | UN-001, UN-002 |
| `alerting` | `design/alerting.md` | UN-001 |
| `dashboard` | `design/dashboard.md` | UN-001, UN-002 |

- **A user need is addressed by multiple contexts.** `UN-001` is satisfied by
  `ingestion`, `alerting`, and `dashboard`.
- **A context satisfies multiple user needs.** `auth` satisfies UN-002 and
  UN-003; `dashboard` satisfies UN-001 and UN-002. Many-to-many.

## Verification (automated)

Each user need is refined into **design inputs** (declared in the per-context
design documents, `kind: design`), realised by
the contributing contexts. Verification is anchored on the design input
(§820.30(f): output meets input): each is verified by an `@allure.story("DI-…")`
test — the test *is* the acceptance criterion ("live BDD"). A user need is met
when validated **and** every design input that `traces_to` it is verified,
**aggregated across every context that realises it** — `UN-001` is not fully
verified until DI-1 and DI-2 (across `ingestion`, `alerting`, *and* `dashboard`)
pass.

## Validation (human + formative)

- **Summative (record of truth)** — clinician usability study (IEC 62366-1) and
  simulated use, on the journeys UN-001 / UN-002.
- **Formative (supporting)** — AI-persona runs driving the dashboard UI
  (`personas/`, results in `persona-results/`), reported by:

  ```bash
  rdm story persona \
    --vv-plan examples/vitalview-samd/docs/verification_and_validation_plan.md \
    --persona-results examples/vitalview-samd/persona-results
  ```

  UN-003 is not a UI journey, so no persona covers it (`not_run` is expected —
  it is validated by security/access review instead).

## A user need is *met* when

it is **validated** (human summative evidence accepted) **and** fully
**verified** (all acceptance criteria pass across contexts). That is the
release-gate condition.
