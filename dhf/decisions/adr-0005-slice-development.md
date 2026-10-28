# ADR 0005: Slice development: the slice is the unit of work

> Status: Accepted, 2026-10-28, by the spec owner. Carried into the spec by
> spec-33. Builds on ADR 0004 (the spec grouped by slice).

## Context

ADR 0004 grouped the spec by slice and made a slice the scope of a rebuild.
The work itself still ran spec-first: a cycle built whatever a spec change
touched, its checklist, score and status were the whole spec's, and nothing
said what a slice was for, whether it was finished, or what was decided in
it. Löyly works the other way round (loyly:docs/wow/way-of-working.md@17d68d2,
sections 1, 2 and 6): work is delivered in vertical slices, each planned,
specified in Gherkin, built, verified and closed; a behaviour change starts
as a slice ("no plan entry, no code"); and every divergence from a slice's
plan is a decision recorded in that slice. The spec owner chose that: slice
development, with the spec as the sum of its slices.

## Decision

- **The slice is the unit of work.** A slice goes planned, ready, building,
  done, and says so in its plan (`status:` under its title). A behaviour
  change starts in a slice: a new one, or an existing one's plan and
  scenarios, never a feature file outside every slice (ADR 0004 already
  refuses that).
- **A cycle builds one slice** (`cycle.edn` `:slice`), and with it every
  component the slice reaches, whole. The builder reads the slice's plan
  first. A whole-project cycle builds every slice, in the order of their
  numbers; it stays due every fifth cycle.
- **A slice's checklist, score and status are its own.** The checklist marks
  each item as the slice's own scenario or as a regression item (a scenario
  of a reached component that sits in another slice); the score and the
  scoreboard count both, apart; status reports each slice: its plan's status
  and its scenarios verified of all; release can be asked of one slice.
- **A slice is done** when its last cycle was accepted and every one of its
  scenarios is verified; the spec owner then sets `status: done`. A slice
  holding planned work (`@wip`) cannot be done.
- **Decisions live in the slice.** Each slice keeps `decisions.md`: what the
  spec owner accepted from a cycle's `decisions.yaml`, and every divergence
  from the plan, with the branch it pruned. The cycle's `decisions.yaml`
  stays the record of what a builder decided.
- **The cut is unchanged:** `spec-N` tags the whole spec, the sum of its
  slices; a cycle names the cut and the slice it builds.

## Consequences

- Scoring moves from "how complete is the spec" to "how complete is this
  slice, and did building it break another". The regression items are what
  catch a shared component going wrong.
- rdm-spec's own slices start with `status: done` for 01 to 10, built and
  accepted through cycle 24; 11 and 12 are `building` (spec-32's new
  scenarios are not built); 13, slice development itself, is `ready`.
- The way of working (`docs/wow/way-of-working.md`) runs a cycle per slice.
