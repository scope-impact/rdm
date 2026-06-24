# ADR 0001 — User needs and SDDs per bounded context

> Status: Proposed (design only; not yet implemented).

## Context

We model **one SDD per bounded context** (DDD): each context — `auth`,
`records`, `audit`, … — has its own design, model, and ubiquitous language.

The open question: **where do user needs live**, and what happens when a need
spans contexts?

Earlier we noted that putting cross-cutting user needs inside a single global
SDD is wrong (a need that spans contexts can't be owned by one output
document). But with **per-context** SDDs the situation changes, and the natural
question is: *can a user need also be per bounded context, living with its
SDD?*

## Decision

Adopt a **two-level need model**.

### Level 1 — Context needs (capabilities) — REQUIRED
Each bounded context owns its needs, **co-located in that context's SDD**
frontmatter. These are context-scoped, so the need and the SDD are at the same
altitude and co-location is correct.

```yaml
# documents/sdd/auth.md
---
id: SDS-AUTH-001
context: auth
user_needs:
  - {id: AUTH-UN-001, text: "Authenticate a clinician via SSO"}
---
```

If your needs **partition cleanly by context**, this is all you need — and the
cross-cutting problem never arises.

### Level 2 — Product needs (journeys) — OPTIONAL
For a need that spans contexts, add a thin **product-level** need that
**composes** Level-1 context needs. It does not restate design; it maps a
journey across contexts. Like context needs, it lives in **frontmatter** — in
the **system architecture document** (the design document at the product
altitude), not a separate data file:

```yaml
# documents/architecture.md  (system / product-level design document)
---
id: SDS-SYS-001
title: System Architecture
product_needs:
  - id: PN-001
    text: "A clinician can securely retrieve a patient record"
    composed_of: [AUTH-UN-001, RECORDS-UN-003, AUDIT-UN-002]
---
```

A cross-cutting need is therefore **referenced, never duplicated**: each context
still owns its own piece; the product need is the seam. Needs live in the
frontmatter of the design document at their altitude — context needs in the
context SDD, product needs in the architecture document — one mechanism, two
levels.

## ID scheme

Context-scoped IDs make ownership obvious and collision-free:

- Context need: `<CONTEXT>-UN-NNN` (e.g. `AUTH-UN-001`).
- Product need: `PN-NNN`.

Allure tags reference **context** need IDs (`@allure.story("AUTH-UN-001")`) —
verification happens at the context level, where the tests live.

## Traceability (where the many-to-many actually is)

| Relation | Cardinality | Source |
|----------|-------------|--------|
| context need → design | 1 ↔ 1 context (same SDD) | the context's SDD |
| context need → tests | 1 ↔ N | Allure tags |
| product need → context needs | **N ↔ M** | `composed_of` |

The many-to-many lives **only** at the product↔context seam. *Within* a context
everything is clean and self-contained. A product need is **verified** iff every
context need it composes is verified.

## Gates (when implemented)

- **design-gate**: each context SDD present, complete, approved; each context
  need declared; each product need's `composed_of` resolves to real context
  needs.
- **release-gate**: every context need verified by a passing Allure test; a
  product need passes iff all its composing context needs pass.

## Why this rehabilitates "needs in the SDD"

The earlier objection was: *user needs are cross-cutting, the SDD is scoped —
don't put a cross-cutting thing in a scoped doc.* Decomposing needs to context
scope removes the mismatch: a **context** need and its **context** SDD are the
same altitude, so co-location is now the right call, not a smell. The
cross-cutting concern is lifted out to the optional product layer, which holds
**references, not copies**.

## Pros / cons

**Pros**
- Each context is self-contained: needs + design + tests co-located, owned, and
  gated together.
- No cross-context duplication; cross-cutting needs are composed by reference.
- Scales by addition: a new context is a new SDD, nothing global to edit.
- Needs-in-SDD becomes correct (same-altitude co-location).

**Cons**
- Cross-cutting journeys require the extra product layer + composition.
- Two ID levels and two-level verification aggregation to maintain.
- Risk if teams skip Level 2 and let a real cross-cutting need hide as one
  context's local need (loses the journey view).

## Consequences (implementation outline, deferred)

- `record/sdd.py`: discover **all** SDDs (`glob documents/sdd/*.md`), read each
  context's `user_needs`; optionally load the product-need registry and resolve
  `composed_of`.
- Templates: per-context SDD frontmatter gains `context` + `user_needs`
  (id+text); optional `product_needs` in the architecture document frontmatter
  + a product-traceability section.
- Gates + traceability matrix: extend to the two-level model (context coverage,
  product composition, verification roll-up).

## Litmus test for this ADR

> Can two contexts both contribute to the same user journey without either
> copying the other's need? Yes — each owns its context need; the product need
> composes both by reference.
