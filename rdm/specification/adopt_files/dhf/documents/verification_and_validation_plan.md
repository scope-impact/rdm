---
id: VVP-001
title: Verification and Validation Plan
---

# Purpose

Defines how this product is verified (does it meet its requirements?) and
validated (does it meet the user needs and its intended use?).

# The spec

RDM does not hold the requirements: it reads them from the product's spec,
written the Löyly way in `.pi/` (`rdm adopt` lays down a skeleton when the
repository has none): a plan of slices in `.pi/plan/`, each linking to a
bounded context in `.pi/spec/<context>/`. A repository that keeps plain
Gherkin under `spec/` or `features/`, or names its spec as `spec` in
`dhf/config.yml`, is read the same way.

- **Requirements** are the accepted scenarios of each context's
  `spec.feature`, each stamped with an id (`@item-NN-SSS`, NN its slice),
  written as concrete Given/When/Then examples and tagged with the Allium
  rule or guarantee they cover.
- **User needs** are the screens of each context's event model, with the
  Allium surfaces they link (who they face, the rules they provide, what they
  guarantee) and the event-storm events behind them. A requirement addresses
  the needs whose rules or guarantees its tags name. (In plain Gherkin, each
  Feature is a need.)

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
