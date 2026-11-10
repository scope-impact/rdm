---
id: VVP-001
title: Verification and Validation Plan
---

# Purpose

Defines how this product is verified (does it meet its requirements?) and
validated (does it meet the user needs and its intended use?).

# The spec

The product is Löyly's pi harness: the regeneration harness that Löyly's
spec v2 specifies (`.pi/spec/`, stamped and planned in `.pi/plan/`) and that
its builder generated into `harness-impl/`. RDM reads the spec from the
`loyly` submodule (`spec` in `dhf/config.yml`); it never writes it, and
Löyly knows nothing of RDM.

- **Requirements** are the spec's stamped Gherkin scenarios, `@item-NN-SSS`,
  in the slices the plan has started (a planned or backlog slice is skipped).
- **User needs** are the screens of each context's event model, with the
  Allium surfaces they link (who they face, the rules they provide, what
  they guarantee) and the event-storm events behind them; in a context with
  no screens, each Allium surface is a need. A requirement addresses the
  needs whose Allium rules or guarantees it is tagged with, else the needs
  of its context.

# Verification approach

Each requirement is verified by the harness's own acceptance suites
(`harness-impl/NN-<context>/test.sh`, clojure.test), run unchanged by
`run-acceptance.sh`. A test claims its requirement by its name
(`item-01-001-…`) or its `:item` metadata; `bridge/user.clj`, loaded into
each test JVM from outside Löyly, writes every test as an Allure result with
a `tag` label of that id. `rdm story release-gate` then enforces that every
requirement is verified by a passing test and every user need is addressed.
Whether a test genuinely verifies its scenario is judged in Löyly's own
review, not here.

# Validation approach

Validation is the operator's: a need is validated when an operator has run
a regeneration cycle through it and approved the result. Each approval is
recorded in `dhf/validation/<name>-validation.json` with the need's id as
`user_need`. Formative evidence (Löyly's graded verdicts) never gates
release and is not evidence here.
