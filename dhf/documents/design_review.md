---
id: DR-001
revision: 16
title: Design Review — RDM
---

# Purpose

Records the design review of RDM's design inputs (DI-001) and the record-first
architecture.

# Design Review 1 — Design inputs and architecture

**Scope reviewed:** Design inputs (DI-001) and the system architecture
(`architecture.md`), against the record-first model in
`docs/record-first-architecture.md` and ADR 0001.

**Disposition:** Approved.

## Participants

Recorded via the version-control history: the reviewers are the approvers of the
pull request in which this review was merged. At least one approver is
independent of the authoring of the reviewed design stage (approver ≠ commit
author).

## Items reviewed

- Design inputs DI-1..DI-6 are unambiguous and individually verifiable by an
  automated test.
- Each user need (V&V plan) is addressed by at least one bounded-context design
  document via `satisfies`; no user need is duplicated across contexts.
- The record core (record, gating, verification, validation, rendering) does not
  depend on the planning extra; planning is fenced as non-record (DI-6).
- Approval is the version-control record; no duplicate sign-off is introduced.
- Verification (Allure) and validation (human summative + persona formative) are
  distinguished; persona evidence never gates release.

## Test-faithfulness review (per design input)

The detailed §820.30(e) examination of *whether each verifying test actually
verifies its design input* is recorded as machine-checkable, hash-pinned verdicts
under `dhf/faithfulness/` (`rdm story faithfulness` reconciles them). At this
revision every design input (DI-1..DI-6) carries a current `faithful` verdict;
the release gate blocks if any is missing, negative, or stale (test changed since
review). One weak test was found during this review (DI-6 originally asserted only
the wording of `PROVENANCE_NOTE`) and was strengthened to assert the actual
stamping behaviour before being marked faithful.

## Findings and actions

- No blocking findings. The release gate and traceability still read the
  user-need registry from per-context SDD frontmatter in code; migrating them to
  read the V&V-plan registry (ADR 0001 consequence) is tracked as follow-up and
  does not block this review.

# Design Review 2 — Agent enablement and document control

**Scope reviewed:** the design inputs added for agent-era contributor
enablement and document control — DI-22 (design-input scaffolding,
`rdm story new-input`), DI-23 (record-first-aware traceability audit), DI-24
(brownfield adoption, `rdm adopt`), and DI-25 (Part 11 document-control
checklist + RDM's own document-control statement, `document_control.md`) —
together with the user needs they refine (UN-010, UN-011) and the canonical
change procedure (`AGENT_WORKFLOW.md`).

**Disposition:** Approved.

## Items reviewed

- Each new design input is unambiguous, owned by exactly one context
  (scaffolding, story_audit, gap_analysis), and individually verified by an
  `@allure.story`-tagged acceptance test.
- Every user need in the V&V plan registry is addressed by at least one design
  input; the release gate enforces the full denominator.
- The document-control statement's Part 11 mapping is held to the shipped
  `part11_document_control` checklist by an executable, falsifiable check.
- Independence of the §820.30(e) faithfulness review was exercised, not just
  asserted: reviews for DI-22 and for the worked example's DI-6..8 initially
  returned `partial` with executed surviving mutations; the verifying tests
  were strengthened and re-reviewed to `faithful`. The partial verdicts and
  the strengthening commits remain in history as the audit trail of the loop
  working.
- The pre-commit design gate blocked an implementation commit staged ahead of
  its design approval during this cycle (DI-25) — the enforced sequencing
  operates as designed.

## Findings and actions

- No blocking findings. The follow-up from Review 1 (migrating the release
  gate to read the V&V-plan registry) is implemented; the release-gate
  denominator is the union of `design_inputs` reconciled against the registry.

# Design Review 3 — Soundness hardening, release artifacts, audit response

**Scope reviewed:** the design inputs added or amended after Review 2 —
DI-26 (design-gate-only hooks default), DI-27 (replayable probes, report
filters), DI-28 (per-verdict hash scope), DI-29/DI-30 (DMR index data and the
release evidence bundle, with UN-012), DI-31 (polyglot tag discovery), DI-32
(legacy-workflow deprecation), the DI-21 amendment (defense-in-depth probe
restore after the SIGTERM/stale-pyc incidents), the DI-10/DI-22 amendments
(sound gap reference matching; satisfies-list sync), and DI-33 (summative
validation records + release-gate warnings, raised by external-style audit
finding NC-1).

**Disposition:** Approved.

## Items reviewed

- Each input is owned by one context, individually verified by a tagged test,
  and carries a current independent faithfulness verdict; from DI-26 onward
  verdicts embed executed mutation probes and are replay-verified.
- The incident record (SIGTERM restore gap; same-second stale-pyc; the replay
  timeout) shows root-cause fixes flowing through this same loop, with the
  interim partial verdicts retained as evidence.
- Audit findings NC-4 (checklist completeness: §11.10(h)/(j)), NC-5 (this
  review entry), and NC-6 (retention statement) are dispositioned in this
  revision; NC-1 is dispositioned by DI-33 plus the summative reviews that
  remain a human obligation; NC-2/NC-3 (human PR review and approval; unique,
  signed identities) are open human actions tracked in the pull request.

## Findings and actions

- Open: summative validation records for UN-001..012 (human reviewers, per the
  V&V plan approach table) — the release gate now names each missing record.
- Open: merge via an independent, human-reviewed PR; individual signing
  identities (NC-2/NC-3).

# Design Review 4 — Retiring the faithfulness gate

**Scope reviewed:** removal of the per-design-input faithfulness review —
DI-19 (faithfulness gate), DI-20 (verdict recorder), DI-21 (mutation probe),
DI-27 (replayable probes), DI-28 (verdict hash scope) and the user need they
refined, UN-009 — plus the wording amendments that follow from it: DI-30 (the
evidence bundle no longer carries verdicts), DI-31 (tag discovery no longer
feeds verdict hashing), UN-012, and the document-control statement.

**Disposition:** Approved.

## Items reviewed

- Rationale: the faithfulness verdicts were RDM's own construct, not a
  §820.30 or IEC 62304 requirement. Their hash pins re-opened reviews on
  edits that changed no requirement and no product behavior (a one-line test
  edit, a shared test module, a dependency declaration), and the defects they
  found were overwhelmingly in RDM's own verification machinery. An
  experiment (branch `claude/phoenix-borrowings`, not merged) added six more
  mechanisms to make that cost bearable, which confirmed the direction was
  wrong.
- Independent verification is now the human-reviewed pull request: git is the
  controlled record and audit trail, the ruleset requires an approving
  review by someone other than the author, and CI runs design-gate →
  acceptance tests → verify → release-gate on every change. A passing tagged
  test per design input remains the release condition (DI-3).
- Retired ids (DI-19, DI-20, DI-21, DI-27, DI-28, UN-009) are not reused.
  Their history, verdicts and probes remain in git.

## Findings and actions

- Accepted: a test can pass without proving its requirement; detecting that
  is the pull-request reviewer's job. Teams that want automated evidence can
  run a mutation-testing tool in CI.
- Addendum: the mutation probe is kept as a standalone reviewer tool (DI-34,
  refining the new UN-013), so that judgment can be backed by an executed
  check. It records no verdict and gates nothing; DI-21 stays retired.
- Open (carried from Review 3): summative validation records; human PR review
  and individual signing identities (NC-2/NC-3).

# Design Review 5 — Design record as a linked-data graph

**Scope reviewed:** UN-014, the new `graph` context, DI-35 (RDF projection of
the record) and DI-36 (Oxigraph store, SPARQL query, SPARQL endpoint for AWS
Graph Explorer).

**Disposition:** Approved.

## Items reviewed

- Consistent with RDM-004's settled rule that Markdown is the authoring
  surface and any database is a derived projection: the graph is rebuilt from
  the record, never edited, and Graph Explorer is a browser, not an editor.
- Open-world semantics are explicit: the graph asserts only recorded facts;
  pass/fail judgments stay in the gates (a later SHACL step may express them
  as shapes — not in scope here).
- Store choice: Oxigraph (embedded, SPARQL 1.1, named graphs, persistent,
  actively released); Kuzu ruled out (property graph, archived Oct 2025);
  Jena/GraphDB/RDFox deferred (server, proprietary, or licensed).
- Optional extra: the record core keeps no RDF dependency.

## Findings and actions

- Open: whether this projection is the answer to RDM-004.06 (types as data)
  is decided on that ticket, not here.
- Not in scope: backlog/plan graph, OWL reasoning.
- Addendum: DI-37 adds the gap-analysis half of the record (checklist clauses
  and `[[…]]` reference tags, matched by `rdm gap`'s own code) and DI-38 the
  gate rules as SHACL shapes (pySHACL), held by test to agreement with the
  release gate and `rdm gap`. The coded gates remain authoritative.

# Design Review 6 — Product structure

**Scope reviewed:** the intended use (V&V plan) and the system architecture
(`architecture.md`), restated as four parts — Record, Gates, Graph,
Documents — with the ten existing contexts assigned to them.

**Disposition:** Approved.

## Items reviewed

- No design input, user need or module changes; this is the record of how the
  existing contexts fit together.
- The record is the only thing people and agents write, and only through a
  reviewed pull request. Graph, matrix, documents and evidence are derived
  and never edited — consistent with Design Review 5 and RDM-004.
- The graph is the read interface for agents; it accepts no writes.
- Agent skills are maintained outside RDM (`scope-impact/agent-skills`).

## Findings and actions

- Open: risk management, regulation-to-checklist, and a read-only agent
  interface are named as not yet in scope; each enters as a user need with
  design inputs.
- Open: known measurement faults in the record — duplicate Allure results
  from the docs build, test tags read from string literals, and the Part 11
  document-control claims not linked to any design input.

# Design Review 7 — Measurement faults

**Scope reviewed:** DI-40 (record context) and the three measurement faults
left open by Design Review 6.

**Disposition:** Approved.

## Items reviewed

- Test tags read from string literals: a product defect in Python tag
  discovery. DI-40 reads tags from the syntax tree instead.
- Duplicate Allure results: the docs build and local runs append to
  `dhf/allure-results`, so a second run doubles every result. Not a product
  behaviour: every documented and scaffolded acceptance command now passes
  `--clean-alluredir`.
- Part 11 document control not linked to a design input: closed, not a fault.
  DC-001 is a quality-system document; its links are to the Part 11 clauses it
  claims, which is correct. A document-to-input link type would add
  vocabulary for a picture, not for a question anyone asks.

## Findings and actions

- None open from this review.

# Design Review 8 — Read-only agent interface

**Scope reviewed:** UN-015, DI-41 (MCP server: schema, query, trace,
validate) and DI-42 (read-only), in the graph context.

**Disposition:** Approved.

## Items reviewed

- MCP over stdio, because agent harnesses (Claude Code, Warp, open-source
  harnesses) already speak it; the official `mcp` SDK, in the existing
  `graph` extra rather than a new one.
- Read-only by construction: no write tool, SPARQL Update rejected. An agent
  that wants a change writes it to the record and opens a pull request,
  like anyone else.
- Freshness: projected per call rather than from a built store, since
  projection is cheap; no cache to go stale.
- Kept out: a `propose` tool (that is git), HTTP transport, authentication
  (stdio runs as the local user).
- Fixed in passing: a duplicate `design_inputs` key in the graph design
  document's frontmatter.
- Addendum: DI-35 now projects each user need's identifier and text; the
  graph held only the id, so an agent tracing a need could not read it.

## Findings and actions

- None open from this review.

# Design Review 9 — Risk register

**Scope reviewed:** UN-016; the new `risk` context with DI-43 (read and score
the register) and DI-44 (release-gate rules); DI-45 (risks in the graph,
shapes, agent trace); RDM's own tool-risk register (RMF-001).

**Disposition:** Approved.

## Items reviewed

- A control is a design input. The chain risk → design input → test → result
  needs no new entity, and "every control is verified" is the existing rule
  that every design input has a passing test.
- Built on RDM-004's settled decision-001: a control changes probability,
  never severity, so the residual records probability only.
- Kept small against RDM-004's open tickets: one probability per risk (no
  p1/p2 split, .02), a lookup matrix rather than a formula (.03), and one
  home for severity and probability — the risk entry (.04). Each can grow
  later without changing a register written to this format.
- The gate checks only what a machine can: chain, score, control present and
  declared, residual scored, acceptance named. Substantiating a control in
  the code and ALARP stay with the reviewer and the risk-analysis skill.
- RMF-001 scores RDM's own tool risks; RISK-TOOL-005 is a Medium residual
  accepted with its reason, so the acceptance rule is exercised on the record.

## Findings and actions

- Fixed in passing: `rdm story new-input` wrote a second `design_inputs:`
  key under an empty `[]` (DI-22), and its stub tests failed line-length lint.
- Addendum: DI-34 now runs the test once unmutated first. A test already
  failing was reported KILLED under any mutation — found while probing
  DI-44's test before it passed, the false proof the probe exists to rule out.
- Open: halla-health's registers use the cluster-file Markdown format, which
  the planning-side parser reads; moving them into the record format is work
  on halla-health, not RDM.

# Design Review 10 — Risk evaluation per the requirements skill

**Scope reviewed:** DI-43, DI-44 and DI-45 as amended, measured against the
`requirements` skill (scope-impact/agent-skills); RDM's own risk policy
(RMP-001) and register (RMF-001).

**Disposition:** Approved.

## Items reviewed

- No default policy. A matrix's cells are not ISO 14971 values; evaluation
  needs criteria the project set before the decision, so a register with no
  declared `risk_policy` blocks ("acceptability criteria missing").
- Acceptability is policy data, not code: per level `acceptable`, `justify`
  or `unacceptable`. The fixed Low/Medium/High/Block rules are gone; level
  names are the project's.
- A residual is evaluated only once every control's design input has a
  passing test; until then it is *not evaluated* and blocks. A passing test
  shows the control was built as specified; effectiveness remains the
  reviewer's judgement.
- Safety and security are separate branches: `category`, `stride` for a
  security risk, `linked` to the safety risk it bears on.
- Residual severity is allowed, defaulting to the initial severity. This
  departs from RDM-004 decision-001 ("a control never changes severity"),
  because ISO 14971 lets a control reduce severity and the requirements
  skill records it; decision-001 should be revisited on that map.
- `status: proposed` marks ratings no person has approved, as a warning.
  RMP-001 and RMF-001 were written by an agent and are proposed until a
  maintainer reviews them.

## Findings and actions

- Open: a maintainer to review RMP-001 and RMF-001 and set `status:
  approved` (or change them).

# Design Review 11 — One data model: the record and its graph

**Scope reviewed:** removing DuckDB, the planning tooling and the
story-audit context; UN-007 amended; DI-46 (duplicate ids) in the gating
context; the gates moved to `rdm/gates/`.

**Disposition:** Approved.

## Items reviewed

- The record and its RDF graph are RDM's only data model. DuckDB served
  planning analytics (Backlog and GitHub sync) and SQL in templates, none of
  it part of the record. Planning lives in its own tools (plan-vs-record).
- Retired: DI-6, DI-13, DI-14, DI-23, DI-32 (see the gating design
  document). Removed with them: `rdm story audit | sync | backlog-validate |
  check-ids | validate`, `rdm pm`, `rdm pull`, the template `duckdb` query,
  and the `story-audit`, `analytics`, `github` and `plan` extras.
- UN-007 keeps its verification: DI-46 makes a duplicated user-need or
  design-input id fail the design gate. The retired scan read any file for
  id-shaped strings; DI-46 reads the record, which is what the gates use.
- The gates (`design_gate`, `new_input`, `mutation`) move from the planning
  package to `rdm/gates/`, so the design gate, release gate, mutation probe
  and pre-commit hook need no extra.

## Findings and actions

- Open: scope-impact/agent-skills — the `backlog` and `story-audit` skills
  name removed commands and are updated in the same change.
- Open: projects that used `rdm story sync` or `rdm pm sync` keep their
  planning data in Backlog.md and GitHub; halla-health's Backlog risk
  clusters convert to `kind: risk` documents in that repository.

# Design Review 12 — Findings from the graph's own analysis

**Scope reviewed:** DI-36 and DI-42 amended; DI-34, DI-37, DI-38 and DI-44
narrowed, with DI-47, DI-48, DI-49 and DI-50 split from them; DI-51 (who
landed a change) and DI-52 (no island documents); RISK-TOOL-006 and
RISK-TOOL-007.

**Disposition:** Approved.

## Items reviewed

- Security, found in review: `rdm graph serve` was read-write with open
  CORS (a cross-origin `CLEAR ALL` emptied the store), and the agent's
  `query` let `SERVICE` reach the network. Both now refused; recorded as
  RISK-TOOL-006 and RISK-TOOL-007, which RISK-TOOL-004 had not covered.
- Provenance: the graph showed only authorship, and the latest commit
  touching a file is never the merge that landed it. DI-51 records the
  landing commit. It is named *landed*, not *approved*: git cannot see
  reviewers, and the graph claims only what git records.
- Islands: the V&V plan, the risk policy and the design review connected to
  nothing. DI-52 links needs, risks and design documents to them. The
  architecture and matrix template stay unlinked: neither declares anything.
- Requirement size: four design inputs ran past 120 words with one test
  each. Each is split along its existing clauses so a failing test names
  the clause.

## Findings and actions

- Open: the reviewer of record lives on the forge (GitHub); projecting it
  is a later design input.

# Design Review 13 — Test evidence

**Scope reviewed:** DI-30 amended (raw results in the release bundle) and
DI-53 (steps and attachments in the graph and in `trace`).

**Disposition:** Approved.

## Items reviewed

- The evidence of record was a status: no test attached anything, results
  had no steps, and the bundle kept RDM's summary, not the results. Once CI
  artifacts expired, nothing an auditor could open remained.
- The bundle now retains the results and their attachments. No hashes of
  its own: the GitHub artifact upload already records a digest.
- Steps and attachments are projected, not judged: no gate requires an
  attachment, since a test can prove a clause without one and a rule would
  invite empty attachments. The graph shows which runs carry evidence.
- RDM's own acceptance tests record a step per clause and attach what they
  checked, starting with the gate and risk tests.

## Findings and actions

- None open from this review.

# Design Review 14 — Allure results as RDF

**Scope reviewed:** DI-54 (results in full), DI-55 (fixtures), DI-56 (runs
to code) in the graph context; the execution projection moves to its own
module, `rdm/graph/allure.py`.

**Disposition:** Approved.

## Items reviewed

- Allure has no RDF vocabulary. Runs stay `rdm:TestRun ⊂ prov:Activity`,
  with PROV-O times; labels are kept as name/value pairs rather than one
  property per label name, so an unknown label needs no vocabulary change.
- Attachment content stays in the files the bundle retains; the graph holds
  the reference, not the bytes.
- DI-56 closes the code end of the chain the record already carries: the
  `output` label is on 40 of RDM's 44 runs.
- Split into three design inputs so none outgrows one test.
- Alongside, not a design input: RDM's own acceptance tests record a step
  per clause, from the clause comments they already carry.
- Addendum: Allure is for acceptance (end-to-end) tests only. They carry
  the tags, steps and attachments, and each run attaches the text of the
  design input it verifies; unit tests carry none, held by
  `tests/allure_scope_test.py`.

## Findings and actions

- None open from this review.

# Design Review 15 — The Allure behaviors hierarchy

**Scope reviewed:** DI-31 and DI-40 amended (only `story` names a design
input); DI-57 (`rdm.pytest_plugin`), in the verification context.

**Disposition:** Approved.

## Items reviewed

- Allure's epic → feature → story is RDM's user need → bounded context →
  design input. Until now both `story` and `feature` named a design input,
  which left Allure's own hierarchy unusable. `feature` and `epic` now carry
  the context and the needs; `story` alone is the verification link.
- Derived, not typed: the plugin reads the record when the test runs, so a
  re-homed input or a new need changes the labels without touching a test.
- The link pins the design document at the tested commit, so the report
  shows the requirement as it was, not as it is now.
- Opt-in (`pytest_plugins`), so installing RDM changes no one's test run.
- Allure remains for acceptance tests only.
- Addendum: DI-57 links every Markdown document that declares the input, not
  only its design document — the V&V plan where its user needs are declared
  and the risk document of each risk it controls — each pinned to the tested
  commit, so a reviewer reaches the whole record from one result.

## Findings and actions

- None open from this review.

# Approval

Recorded in version control (the merged, reviewed PR), per the design-input
approval model. No sign-off table is duplicated here.
