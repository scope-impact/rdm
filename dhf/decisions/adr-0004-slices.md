# ADR 0004: The spec is grouped by slice, and a slice is rebuilt whole

> Status: Accepted, 2026-10-28, by the spec owner. Carried into the spec by
> spec-30 (the slices layout); rdm-spec's own spec moves onto it in spec-31,
> once a build that reads slices is installed.

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

## The slices of rdm-spec's own spec (spec-31)

Each slice reaches the components named; the rules move from the feature files
named, unchanged and keeping their ids.

| Slice | Rules from |
|---|---|
| `01-start-a-project` | project/init, project/adopt, project/hooks; kernel/command "Everything runs through one command", "Every task reports the same way" |
| `02-write-the-spec` | spec/parser "read at a revision", "declared subset"; spec/stamper; spec/check |
| `03-ground-the-spec` | knowledge/sources, knowledge/wiki |
| `04-model-the-architecture` | architecture/model, architecture/lint, architecture/views |
| `05-verify-by-results` | kernel/runner; verification/results, status, trace; verification/release "every scenario verified" |
| `06-regenerate` | regeneration/cut, scopes, fence; verification/verify but its :plugin check |
| `07-measure` | measurement/checklist but its regulated rule; measurement/scorer, scoreboard; agency/observe |
| `08-plugins` | kernel/plugins, kernel/bus, measurement/oracle; kernel/command "A gate stops the task"; verify's :plugin check |
| `09-publish` | publishing/site |
| `10-regulated-profile` | compliance/gap, compliance/audit; checklist's and release's regulated rules |
| `11-project-in-a-folder` | kernel/command "A project may be one folder"; verify's spec-0370 |
| `12-several-specs` | spec/parser "one spec, or several" |

A slice reaches exactly the components it holds feature files for, never one
its plan only names. Where a rule written in one component reaches others
(spec-29's several spec folders reach stamper, check, checklist, verify,
status, trace and release from one parser rule), spec-31 gives the slice a
scenario for each of them, in a feature file named for it, so the slice's
scope is read from the spec and never widened by hand.

## Consequences

- A slice scope rebuilds more than a component scope did: `06-regenerate`
  alone reaches about a quarter of the code. That is the point: the slice is
  what a change means. The whole-project cycle every fifth cycle stays.
- The move (spec-31) is one change of paths and of the model's spec paths,
  checked by a release that reads slices. Its cycle is whole-project.
