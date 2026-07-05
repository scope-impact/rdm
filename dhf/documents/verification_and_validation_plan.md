---
id: VVP-001
title: Verification and Validation Plan — RDM
# User-need registry (ADR 0001): RDM's validated journeys, defined once here.
# Per-context SDDs reference these via `satisfies`. Verification = acceptance
# criteria as @allure-tagged tests in RDM's tests/, aggregated across contexts.
# Validation = human review + the usability-persona skill (formative).
user_needs:
  - id: UN-001
    text: "A regulatory author can compile a Design History File from the system of record (SDD + executed tests + git)."
  - id: UN-002
    text: "A team is prevented from transitioning into implementation until the design input and design review are approved."
  - id: UN-003
    text: "A release is blocked until every user need is verified by a passing test."
  - id: UN-004
    text: "Each user need's verification status is traceable from executed test results."
  - id: UN-005
    text: "The usability of a documented UI can be exercised formatively against a user need."
  - id: UN-006
    text: "A regulatory author can check that documents contain the references a chosen standard/checklist requires (gap analysis)."
  - id: UN-007
    text: "A regulatory author can detect requirement-ID conflicts and locate every ID definition across the project (traceability integrity)."
  - id: UN-008
    text: "A regulatory author can scaffold a new compliant documentation project from a single command."
  - id: UN-009
    text: "A release is blocked unless each design input's verifying test has been independently confirmed to actually verify it (not merely pass)."
  - id: UN-010
    text: "A contributor (human or agent) is guided to author a new design input that is fully traced: declared in its owning context, verified by a tagged test, and carried through the gates."
  - id: UN-011
    text: "An existing repository (brownfield, little or no documentation) can be brought under record-first design controls from a single command, without disturbing its current contents."
  - id: UN-012
    text: "A release's evidence (verification data, traceability matrix, faithfulness verdicts) and the device-master-record index can be produced as retained, generated artifacts from the record."
---

# Purpose

Defines how RDM is verified (does it meet its acceptance criteria?) and validated
(does it meet the user needs / intended use?).

# User needs

Declared in this document's frontmatter (`user_needs`) — the validation anchors
and the coverage denominator. Every user need must be validated and fully
verified before a release.

# Verification approach

Each user need is refined into **design inputs** (declared in the per-context
design documents, `kind: design`), realised by
the bounded contexts that `satisfy` the need. Verification is anchored on the
design inputs (§820.30(f): output meets input): each is verified by an automated
test in RDM's `tests/`, tagged `@allure.story("DI-…")` — the test *is* the
acceptance criterion ("live BDD"). A user need is met when it is validated and
every design input that `traces_to` it is verified, aggregated across the
contexts that satisfy it. `rdm story release-gate` enforces this.

# Validation approach

| User need | Summative (record of truth) | Formative (supporting) |
|-----------|-----------------------------|------------------------|
| UN-001..004 | maintainer review that the compiled DHF, gates, and traceability meet the documented intent | dogfooding: RDM compiles its own DHF (this file set) |
| UN-005 | review of persona-skill output against a real UI journey | `usability-persona` skill runs (`rdm story persona`) |
| UN-006 | maintainer review that gap analysis flags real missing standard references against shipped checklists | dogfooding: `rdm gap` over RDM's own released docs |
| UN-007 | maintainer review that ID-conflict and traceability audits catch real duplicates/orphans | dogfooding: `rdm story audit` / `check-ids` over RDM's own requirements |
| UN-008 | maintainer review that a scaffolded project builds a release and passes the relevant gap checklists | dogfooding: `fresh_release_test` builds an init'd project end-to-end |
| UN-009 | maintainer/second-agent review that the faithfulness gate blocks unreviewed/unfaithful/partial/stale verdicts | dogfooding: `rdm story faithfulness` over RDM's own DHF, with an independent reviewer |
| UN-010 | maintainer review that a scaffolded design input lands fully traced (frontmatter entry, tagged stub test, checklist) and that the agent workflow runbook matches the enforced gates | dogfooding: `rdm story new-input` used against RDM's own DHF; agent sessions following `dhf/AGENT_WORKFLOW.md` |
| UN-011 | maintainer review that an adopted repository ends up with the working control surface (DHF skeleton, runbook, hook, bootstrap, CI) and that nothing pre-existing was overwritten | trial adoption into a scratch copy of a real repository; `rdm adopt` acceptance test exercises the skip-not-overwrite contract |
| UN-012 | maintainer review that a produced evidence bundle and DMR index are complete and agree with the record they were generated from | dogfooding: `rdm story dmr` / `evidence-bundle` run against RDM's own DHF and the worked example |

Formative evidence never gates release.
