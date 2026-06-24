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
- It is defined **once**, in a single user-needs registry — the **system
  architecture document** frontmatter:

  ```yaml
  # documents/architecture.md
  ---
  id: SDS-SYS-001
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

- Within each contributing context, a user need **dissolves into acceptance
  criteria** (verified by `@allure`-tagged tests) and, optionally, **backlog
  tasks** (plan).

## V&V mapping

| Question | Against | Evidence |
|----------|---------|----------|
| **Validation** — "right thing?" | the **user need** (journey) | usability (IEC 62366), clinical, simulated/actual use — human, recorded as controlled docs, approved in git |
| **Verification** — "built right?" | the **acceptance criteria** | `@allure` test results, aggregated across **every** SDD that satisfies the need |

A user need is **met** when it is **validated** *and* **all** of its acceptance
criteria — across every context that satisfies it — are **verified**.

## ID scheme

- User need: `UN-NNN` — global, because it is cross-cutting.
- Acceptance criteria / tests reference the user need they verify
  (`@allure.story("UN-001")`); finer AC IDs are optional.

## Traceability (many-to-many)

| Relation | Cardinality | Source |
|----------|-------------|--------|
| user need → SDDs | N ↔ M | each SDD's `satisfies` |
| user need → tests | 1 ↔ N (spanning contexts) | `@allure` tags |
| user need → validation | 1 ↔ 1+ | V&V plan + validation records |

The same user need is **referenced** by many SDDs, never **duplicated**: it is
defined once in the registry; each SDD points at it with `satisfies`.

## Gates (when implemented)

- **design-gate**: each SDD present, complete, approved; each `satisfies`
  resolves to a real user need.
- **release-gate**: every user need is verified (all its acceptance criteria
  pass, aggregated across contexts) **and** has approved validation evidence.

## Why "product need" is dropped

- Validation is against user needs, so the cross-context journey *is* a user
  need — a separate "product need" was a redundant name for it.
- The former "context user need" was really an acceptance criterion /
  requirement (verified, not validated) — it folds into acceptance criteria.

## Consequences (implementation outline, deferred)

- `record/sdd.py`: read the user-needs registry (architecture doc frontmatter);
  glob all SDDs and read each `satisfies`; reconcile coverage.
- Templates: system architecture doc frontmatter `user_needs`; per-context SDD
  frontmatter `satisfies`.
- Gates + traceability matrix: aggregate verification across all SDDs per user
  need; add validation presence/approval as a separate, human-evidenced check.

## Litmus test

> Can two contexts contribute to one user need without duplicating it? Yes — the
> need is defined once in the registry; each SDD references it via `satisfies`,
> and its acceptance criteria are verified wherever they live.
