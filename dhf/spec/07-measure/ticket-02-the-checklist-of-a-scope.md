# Ticket 02: The checklist of a scope: contexts, components, specs and slices

Slice: `07-measure` (`plan.md`, Scope In: "The checklist, derived from the
cut"; "The scorer: a cycle is one directory"); `scorer.feature`, Rule "A
cycle is one directory, declared before it is verified";
`12-several-specs/checklist.feature`; `13-slice-development/checklist.feature`
and `13-slice-development/scorer.feature`, Rule "A cycle builds a slice";
the glossary's "Scope", "Slice", "Slice development" and "Spec folder".
Builds on ticket 01.

## What it delivers

measurement/checklist's `run` for every scope a cycle.edn may declare, its
items those of the scope and nothing else (the glossary's "Scope"), the
same set verify's tests check requires for the same cycle (ED-05; slice
06's ticket 07):

- `:project`, or no `:scope` and no `:slice` (G-04): every stamped scenario
  of the spec at the cut.
- `{:context C}` and `{:component C/N}`, alone or in a list: the scenarios
  whose owning component is in the context (the kernel counting as the
  context `kernel`) or is the component, in whichever slice they sit
  (spec-0200, spec-0173); a context or component the cut's model does not
  hold, exit 2 naming it (G-04).
- `{:spec S}`: the stamped scenarios of the spec named, none of another
  (spec-0389); a spec the cut's configuration does not declare, exit 2
  (G-04).
- `{:slice S}`, or `:slice S` read as `[{:slice S}]` (spec-0417): the
  scenarios of every component the slice reaches, wherever their slice
  (spec-0395; ED-05, G-12), each item naming its slice (`"slice"`) and its
  role, `"slice"` for one in S, `"regression"` for one elsewhere
  (spec-0414; G-07); a slice the cut does not hold, exit 2 naming it, read
  from the cut's tree with regeneration/cut's `tree` (spec-0396; ED-03);
  both `:slice` and `:scope`, exit 2 naming both (spec-0417).
- `planned`: the scope's @wip scenarios only (G-08).

## Scenarios it satisfies

- `@spec-0173` A cycle is declared by hand in dhf/cycles/NN/cycle.edn, `scorer.feature`
- `@spec-0200` A scope is the project, one context or one component, or a list of contexts and components (4 rows), `scorer.feature`
- `@spec-0389` A cycle may be scoped to one spec, `12-several-specs/checklist.feature`
- `@spec-0395` A slice scope's checklist holds every scenario of every component the slice reaches, `13-slice-development/checklist.feature`
- `@spec-0414` Each item of a slice cycle says whether it is the slice's own or a regression, `13-slice-development/checklist.feature`
- `@spec-0396` A cycle may be scoped to a slice, `13-slice-development/scorer.feature`
- `@spec-0417` A cycle names the slice it builds, and nothing else to scope it, `13-slice-development/scorer.feature`

7 scenarios, verified in cycle 8. spec-0173's, spec-0389's and spec-0395's
Whens also run verify (slice 06, built in cycle 7): their last Thens hold
the checklist and verify's tests check to the same scenarios.

## Components and interfaces it touches

measurement/checklist (`read-cycle`, `run`), regeneration/cut (`tree`, as
slice 06 built it), spec/parser (`parse`); verification/verify as slice 06
built it, unchanged.

## Done when

measurement/checklist's unit tests cover each kind of scope part, a list of
them, the kernel as a context, each refusal above, a slice reaching a
component whose scenarios sit in two slices, and a scope of one spec of
two; `wasm/test.sh` writes a passing result for each scenario above;
earlier tickets' results unchanged.
