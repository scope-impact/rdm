# RDM as a record compiler: SDD + Allure + git → DHF

> Status: proposal / design sketch. Describes a target shape for RDM, not the
> current implementation. Project management is explicitly **out of scope** of
> the record.

## Thesis

RDM is a **record compiler**. Its only inputs are the *system of record*; its
only output is the Design History File (DHF). Project management is external
and optional — RDM never depends on it.

```
System of record (inputs)            RDM (compiler)              Output
---------------------------          ---------------------       --------------
SDD + data/*.yml  ─┐
 (design, user_needs)               ingest → reconcile           DHF
Allure results    ─┼──────────────► → gate → render ──────────►  (Markdown →
 (verification evidence)                                          PDF / DOCX)
git history       ─┘
 (approval, change)
```

## The three record inputs

| Input | Design-control meaning | Source of truth for |
|-------|------------------------|---------------------|
| **SDD + `data/*.yml`** | the design and the user needs (the *what* and *how*) | requirements / design, `user_needs` IDs |
| **Allure results** | executed **verification evidence** linked by `@allure.story/feature("UN-…")` | acceptance criteria status (pass/fail) |
| **git history** | approval (reviewed/merged PRs, `reviews_required`) + change history + revision/baseline | who approved, what changed, when |

This triad is a self-sufficient DHF spine: *requirements → verification evidence
→ approval/change record.* Delete every plan artifact and it is unchanged.

## User needs: where they live (per ADR 0001)

User needs are part of the system of record. There is **one** need concept — the
**user need** — and it is **cross-cutting**: multiple bounded-context SDDs may
address the same user need (it is solution-independent and does not respect
context boundaries). "Product need" vocabulary is not used.

| Artifact | Role | Lives in |
|----------|------|----------|
| **user need** (validated, cross-cutting) | the journey / intended use; the **validation** anchor | defined once in the **system architecture document** frontmatter (`user_needs: [{id, text}]`) |
| **`satisfies`** reference | which user needs a context's design contributes to | each per-context **SDD** frontmatter (`satisfies: [UN-…]`) |
| **acceptance criteria** (verified) | the conditions a user need dissolves into within a context | the context's tests, tagged `@allure.story("UN-…")` |

Rules:

- A user need is **referenced** by many SDDs, never **duplicated** — defined
  once in the registry; each SDD points at it via `satisfies`.
- **Validation** is against the user need (human evidence — usability, clinical,
  simulated use). **Verification** is against its acceptance criteria (Allure),
  aggregated across **every** SDD that satisfies the need.
- A user need is **met** when validated **and** all its acceptance criteria
  (across contexts) are verified. See ADR 0001.

## What RDM does

1. **Ingest**
   - `record/sdd.py` — read the user-needs registry (system architecture
     document frontmatter `user_needs`); discover all per-context SDDs and read
     each one's `satisfies` and design data.
   - `record/allure.py` — read an Allure results directory → per-user-need
     verification status (aggregated across the SDDs that satisfy it).
   - `record/history.py` — git/PR → approvals + change history (reuse the
     existing `project_management/github.py` change/approval logic, reframed as
     "git is the record", PM-agnostic).
2. **Reconcile / trace** (`trace.py`) — join user-need IDs ↔ Allure tags across
   contexts:

   | Status | Meaning |
   |--------|---------|
   | verified | user need has ≥1 Allure test, all passed |
   | failed | has tests, some failed |
   | untested | declared user need, no Allure test (coverage gap) |
   | orphan | Allure tag with no matching user need |

   Verification is aggregated across every SDD that satisfies the need;
   **validation** (human evidence on the journey) is tracked separately.
3. **Gate** (`design_gate.py`, existing) — design input/review present +
   complete + approved (committed) in git; baseline drift re-opens the gate.
4. **Render** (existing pipeline) — templates + data → Markdown → PDF/DOCX, now
   also embedding **generated** sections:
   - traceability matrix (user need → SDDs → test → status),
   - V&V / test record (from Allure pass/fail, timestamp, version),
   - revision/change history (from git).

## What is NOT RDM: project management

```
PM tool (Backlog.md / GitHub Issues / Jira / none)
      │  coordination only — "who does what, when"
      │  may produce commits / PRs
      ▼
    git history  ──►  ingested by RDM
```

- PM is **coordination scaffolding**, not a record. A human team may want a
  board; an agent may decompose work on the fly and keep nothing.
- The **only** PM → record path is via **git** (commits/PRs). RDM never ingests
  a PM tool directly.
- Plan artifacts are **never cited as evidence.** Any synced output (backlog,
  issues, DuckDB) is stamped *"derived planning data — not a controlled
  record."*
- The existing `backlog` / `pm sync` / DuckDB code becomes an optional,
  detachable convenience (e.g. an `rdm[plan]` extra), removable with zero
  regulatory impact.

## The contract at the boundary

RDM depends only on:

1. a git repository,
2. an SDD declaring `user_needs`, and
3. an Allure results directory.

Nothing about *how* the work was planned. Anyone — human or agent — contributes
by editing the SDD, writing `@allure`-tagged tests, and committing via reviewed
PRs. That is the whole interface.

## Module shape (target)

```
rdm/
  record/            # system-of-record ingest layer (new)
    sdd.py           #   SDD frontmatter user_needs + design data
    allure.py        #   Allure results dir -> verification status
    history.py       #   git/PR -> approvals + change history
  trace.py           # reconcile user_needs <-> allure -> matrix + coverage
  design_gate.py     # existing gate (present + complete + approved)
  render.py / gaps/  # existing -> consume record, emit DHF sections
  plan/              # OPTIONAL extra: backlog / pm sync / duckdb (non-record)
```

## What changes vs today

- **Allure results become a first-class input.** Today only `@allure` tags are
  scanned from source; the executed *results* are not ingested. Add the results
  ingester so verification *status* (not just linkage) enters the DHF.
- **Traceability matrix and V&V become generated DHF sections** from SDD↔Allure,
  not hand-maintained tables.
- **Change/approval history is sourced from git**, framed PM-agnostically.
- **The PM pipeline is demoted** to an optional, clearly-fenced extra.

## What stays the same

- The design gate (present + complete + approved, baseline-drift aware).
- The Jinja render pipeline, templates, and `data/*.yml` model.
- `rdm gap` checklist verification.

## Migration path

1. Add `record/allure.py` (results ingester) + verification status — new
   capability, no breakage.
2. Make the traceability matrix and V&V/test-record DHF sections generated.
3. Reframe `pm`/`backlog`/DuckDB as an optional `rdm[plan]` extra; add the
   plan-vs-record boundary note and provenance stamps.
4. (Later) drop or spin out the plan pipeline entirely.
