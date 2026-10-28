# ADR 0004: The spec is grouped by slice, and a slice is rebuilt whole

> Status: Accepted, 2026-10-28, by the spec owner. Carried into the spec by
> spec-30 (the slices layout); rdm-spec's own spec moved into slices in
> spec-31, a component's file whole, and splits by rule once a build that
> reads slices is installed.

## Context

The spec is grouped by bounded context, then by component
(`dhf/spec/<context>/<component>.feature`), and a cycle's scopes are the
components whose feature files a spec change touched. A piece of behaviour
rarely sits in one component. spec-29's several spec folders were one rule in
`spec/parser.feature`, yet it changes how stamp, check, fence, checklist,
verify, status, trace and release read the spec: `rdm-spec scopes spec-26
spec-29` named six components, and the integrator added seven by hand
(`dhf/cycles/23/cycle.edn`). A scope read from where a rule is written misses
what the rule reaches; a scope widened by hand is a scope remembered, which
cycle 04 already showed fails (`regeneration/scopes.feature`).

Löyly groups its spec by slice (loyly:README.md@17d68d2, "How to read the
repo", layer 4): `docs/plan/01-booking-core/` holds a plan (goal, references,
scope in and out), the slice's Gherkin and its decisions, and a regeneration
builds the slice again from the docs alone.

## Decision

- **A slice is a piece of behaviour, planned and rebuilt whole.** Under
  `:spec-layout :slices` the spec is `<NN-slice>/plan.md` plus one
  `<NN-slice>/<component>.feature` per component the slice reaches, named by
  the component's name in the model. The bounded contexts stay in the C4
  model; they are no longer spec folders.
- **A component's scenarios are those of every feature file named for it,**
  in every slice: its code is one, so rebuilding it answers to all of them.
- **A change to any file of a slice makes the slice a scope,** and a slice
  scope fences, builds, verifies and grades every component the slice
  reaches. Event scopes still add publishers and subscribers.
- **The context layout stays** for any project that declares no
  `:spec-layout`, the wallet among them.

## The slices of rdm-spec's own spec

Moved first, in spec-31, by the layout the installed release already reads:
each component's feature file whole into one slice, the model's spec paths
following it, and a plan beside each slice's files.

| Slice | Components |
|---|---|
| `01-start-a-project` | init, adopt, hooks, command |
| `02-write-the-spec` | parser, stamper, check |
| `03-ground-the-spec` | sources, wiki |
| `04-model-the-architecture` | model, lint, views |
| `05-verify-by-results` | runner, results, status, trace, release |
| `06-regenerate` | cut, scopes, fence, verify |
| `07-measure` | checklist, scorer, scoreboard, observe |
| `08-plugins` | plugins, bus, oracle |
| `09-publish` | site |
| `10-regulated-profile` | gap, audit |

Split by rule in spec-32, once cycle 24's build read the slices layout,
every rule keeping its id:

- verify's :plugin check went to `08-plugins/verify.feature`; the
  checklist's and the release's regulated rules to `10-regulated-profile`.
- `11-project-in-a-folder` holds command's folder rule, fence's spec-0369,
  verify's spec-0370, and a scenario each for checklist and scopes.
- `12-several-specs` holds parser's rule, and a scenario for each component
  it reaches: check, stamper, checklist, verify, status, trace and release
  (spec-0390's status row became status's own scenario).
- command's "A gate stops the task" stayed in `01-start-a-project`: it says
  how a task reports its stages, not what a plugin does.

A slice reaches exactly the components it holds feature files for, never one
its plan only names, so its scope is read from the spec and never widened by
hand.

## Consequences

- A slice scope rebuilds more than a component scope did: `06-regenerate`
  alone reaches about a quarter of the code. That is the point: the slice is
  what a change means. The whole-project cycle every fifth cycle stays.
- The move (spec-31) is one change of paths and of the model's spec paths;
  the scopes it lists are every component, so the cycle after it is
  whole-project.
