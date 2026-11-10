---
id: VVP-001
title: Verification and Validation Plan — RDM
---

# Purpose

Defines how RDM is verified (does it meet its requirements, the acceptance
criteria?) and validated (does it meet the user needs / intended use?). The
vocabulary is the repository's `CONTEXT.md`.

# Intended use

RDM audits the record of regulated software — the spec it is held to,
checklists, tagged tests, results and their git history — and lets the team
gate releases on it, render regulatory documents from it, and query it as a
read-only graph. Its users are regulatory authors, engineers, reviewers, and
agents working under their direction. RDM is not a medical device and does not
itself establish compliance; it keeps the evidence that a team presents.

# User needs

RDM's user needs are in its spec (`.pi/spec/<context>/allium/needs.allium`):
one Allium surface per need, naming the actor it faces and what it
guarantees. They are the validation anchors. Every user need must be
addressed by a verified requirement before a release, and validated by a
person.

# Verification approach

RDM's requirements are the stamped scenarios of its spec (`.pi/spec/<context>/
spec.feature`, `item-NN-SSS`), one bounded context per slice of `.pi/plan`.
Each is an acceptance criterion, verified by the automated tests in RDM's
`tests/acceptance/` that claim it with `@allure.tag("item-NN-SSS")`. The
test's verification steps are its own, not criteria: a requirement is
accepted as a whole. A user need is met when it is validated and every
requirement addressing it is verified; a requirement that is a risk control
counts once it is verified and its risk's residual is acceptable.
`rdm story release-gate` enforces this.

# Validation approach

| User need | Summative (record of truth) | Formative (supporting) |
|-----------|-----------------------------|------------------------|
| CompileDhf, ReleaseBlocked, TraceVerification | maintainer review that the compiled DHF, gates, and traceability meet the documented intent | dogfooding: RDM compiles its own DHF (this file set) |
| ValidationVisible | maintainer review that the release gate names every user need without an approved validation record | dogfooding: RDM's own release gate warns on its unvalidated needs |
| GapAnalysis | maintainer review that gap analysis flags real missing standard references against shipped checklists | dogfooding: `rdm gap` over RDM's own released docs |
| ScaffoldProject | maintainer review that a scaffolded project builds a release and passes the relevant gap checklists | dogfooding: `fresh_release_test` builds an init'd project end-to-end |
| AdoptRepository | maintainer review that an adopted repository ends up with the DHF skeleton and the CI gates and that nothing pre-existing was overwritten | trial adoption into a scratch copy of a real repository; `rdm adopt` acceptance test exercises the skip-not-overwrite contract |
| ReleaseEvidence | maintainer review that a produced evidence bundle and DMR index are complete and agree with the record they were generated from | dogfooding: `rdm story dmr` / `evidence-bundle` run against RDM's own DHF and the worked example |
| ExploreGraph | maintainer review that the projected graph agrees with the record it was built from (needs, requirements, contexts, documents, results, commits) and is explorable in a graph browser | dogfooding: RDM's own DHF projected, queried, and browsed in AWS Graph Explorer |
| AgentReadsRecord | maintainer review that an agent harness connected to the server answers traceability questions about RDM's own record correctly and has no way to change it | dogfooding: agent sessions on RDM itself using `rdm graph mcp` |
| RiskRegister | maintainer review that the gate's risk findings match a manual review of the same register with the risk-analysis method | dogfooding: RDM's own tool-risk register (`dhf/documents/risk/`) held to the release gate |
| UserManual | maintainer review that the manual's pages teach what the gates enforce | dogfooding: RDM's own manual (`dhf/documents/user_manual.md`), published per release |

Formative evidence never gates release.
