# Ticket 04: Bounded contexts: a view each, relating through events

Slice: `04-model-the-architecture` (`plan.md`, Scope In: "bounded contexts
as modules of one container, relating through events"); `lint.feature`, Rule
"Bounded contexts are modules of one container, and the model owns the
spec", and `decisions.md`, why spec-0339 exists. Builds on ticket 03.

## What it delivers

architecture/lint, over a model read whole:

- `a-component-view`: a bounded context with components and no component
  view, naming the context.
- `a-contract-only`: a relationship from a component of one bounded context
  to a component of another, naming no event, naming the relationship; the
  kernel's components belong to no context and are not held to it. A
  configuration whose `:c4 :contexts-relate-by` holds `:calls` is not held to
  events only: the rule passes (`architecture-lint.c4`).

## Scenarios it satisfies

- `@spec-0012` A bounded context with no component view fails the lint, `lint.feature`
- `@spec-0015` Components of different contexts relate only through an event, `lint.feature`
- `@spec-0339` A project that lets its contexts relate by call is not held to events only, `lint.feature`

3 scenarios, verified in cycle 3.

## Components and interfaces it touches

architecture/lint (`architecture-lint`, its `c4` from the configuration),
architecture/model (`read`).

## Done when

architecture/lint's unit tests cover both rules, a kernel relationship, and
`:contexts-relate-by` with and without `:calls`; `wasm/test.sh` writes a
passing result for each scenario above; earlier tickets' results unchanged.
