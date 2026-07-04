---
id: VVP-001
title: Verification and Validation Plan
# User-need registry: this product's validated journeys, defined ONCE here.
# Per-context design documents reference them via `satisfies`; design inputs
# refine them via `traces_to`. Register a need in the same change as its first
# design input — the release gate blocks any need nothing traces to.
user_needs: []
# TODO register your user needs, e.g.:
#   - id: UN-001
#     text: "A clinician is promptly alerted to dangerous changes in a patient's vitals."
---

# Purpose

Defines how this product is verified (does it meet its design inputs?) and
validated (does it meet the user needs / intended use?).

# User needs

Declared in this document's frontmatter (`user_needs`) — the validation anchors
and the coverage denominator.

# Verification approach

Each user need is refined into **design inputs** (declared in the per-context
design documents, `kind: design`). Each design input is verified by an
automated test tagged `@allure.story("DI-…")` — the test *is* the acceptance
criterion ("live BDD"). `rdm story release-gate` enforces that every declared
input is verified by a passing test AND independently confirmed faithful.

# Validation approach

TODO describe, per user need, the summative evidence (who reviews, against
what) and any formative evidence (e.g. AI-persona usability runs via
`rdm story persona`). Formative evidence never gates release.
