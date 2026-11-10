---
id: VVP-001
title: Verification and Validation Plan
---

# Purpose

Defines how this product is verified (does it meet its requirements?) and
validated (does it meet the user needs and its intended use?).

# The spec

RDM does not hold the requirements: it reads them from the product's
Gherkin spec, written wherever the product is specified. RDM finds it at
`spec/` or `features/` in the repository (or a Löyly plan at `.pi/plan`), or
wherever `spec` in `dhf/config.yml` names.

- **Requirements** are the spec's scenarios tagged with an id (`@REQ-12`,
  `@item-02-001`).
- **User needs** are, by default, the spec's Features, each with its
  description as the need's text; an adapter can read richer needs (a Löyly
  spec's event-model screens and Allium surfaces).

# Verification approach

Each requirement is verified by an automated test whose Allure result claims
it with a `tag` label of its id (a Gherkin runner writes it from the
scenario's tag; a pytest test uses `@allure.tag("<id>")`). `rdm story release-gate`
enforces that every requirement is verified by a passing test and every user
need is addressed; the pull-request review independently confirms each test
genuinely verifies its requirement.

# Validation approach

TODO describe, per user need, the summative evidence (who reviews, against
what) and any formative evidence. Formative evidence never gates release.
Record each approved validation in `dhf/validation/<name>-validation.json`
with the need's id as `user_need`.
