---
id: VVP-001
title: Verification and Validation Plan
---

# Purpose

Defines how this product is verified (does it meet its requirements?) and
validated (does it meet the user needs and its intended use?).

# The spec

RDM does not hold the requirements: it reads them from the product's spec,
written and stamped in the factory that builds the product (a Löyly-shaped
spec). RDM finds it at `.pi/plan` in the repository, or wherever `spec` in
`dhf/config.yml` names.

- **User needs** are the screens of the spec's event model, with the Allium
  surfaces they link (who they face, the rules they provide, what they
  guarantee) and the event-storm events behind them; in a context with no
  screens, each Allium surface is a need.
- **Requirements** are the spec's stamped Gherkin scenarios, `item-NN-SSS`.

# Verification approach

Each requirement is verified by an automated test that claims it with an
Allure tag `item-NN-SSS` (a Gherkin runner writes it from the scenario's tag;
a plain test uses `@allure.tag("item-NN-SSS")`). `rdm story release-gate`
enforces that every requirement is verified by a passing test and every user
need is addressed; the pull-request review independently confirms each test
genuinely verifies its requirement.

# Validation approach

TODO describe, per user need, the summative evidence (who reviews, against
what) and any formative evidence. Formative evidence never gates release.
Record each approved validation in `dhf/validation/<name>-validation.json`
with the need's id as `user_need`.
