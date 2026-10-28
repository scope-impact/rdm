# Ticket 04: The score filed, for a verified cycle whose builder neither grades nor reads it

Slice: `07-measure` (`plan.md`, Scope In: "The scorer: a cycle is one
directory ... only a verified build scored"); `scorer.feature`, Rules "A
cycle is one directory, declared before it is verified", "The builder
neither grades nor reads its own score" and "Only a verified build is
scored"; `13-slice-development/scorer.feature`, Rule "A slice cycle is
scored for its slice, and for what it regressed"; the glossary's "Role",
"Grader", "Verdict" and "Verify". Builds on ticket 02.

## What it delivers

measurement/scorer's `run`, "rdm-spec score CYCLE --verdicts FILE
[--verdicts FILE ...]", from the cycle to score.edn, with one verdicts
file held as ticket 05 holds them all:

- cycle.edn read with the checklist's `read-cycle`: a role left unnamed,
  exit 1 naming it (spec-0144); the builder's name also the grader's or
  the spec owner's, exit 1 saying the builder may not grade or read its own
  score (spec-0143; G-20).
- `dhf/cycles/CYCLE/ratified.edn` read as a file (ED-06): none, or not
  `:outcome :passed`, exit 1 saying the cycle has no passing verification
  (spec-0214; G-17).
- The checklist read with the checklist's `derived` (none: G-16).
- score.edn written beside the cycle: `:cycle`, `:cut`, `:scope` as
  cycle.edn gives it, `:room-tree` from ratified.edn, `:builder`,
  `:grader` and `:spec-owner` each `{:name :actor}` (spec-0142), `:items`
  (every checklist item, each `{:id :verdict :evidence}`), `:passed`,
  `:scored` (spec-0174; G-19); in a slice cycle (a `:slice`, or a scope of
  one slice), also `:slice {:name :passed :scored}` over the items whose
  role is `"slice"` and `:regression {:passed :scored}` over the others
  (spec-0418). Every exit 1 writes nothing; the refusals' order as G-15
  settles. `filed` reads score.edn back.

## Scenarios it satisfies

- `@spec-0174` The score is written beside the cycle, `scorer.feature`
- `@spec-0142` A cycle records who built, graded and reads it, each as human or agent, `scorer.feature`
- `@spec-0143` A cycle whose builder is also its grader or spec owner is refused, `scorer.feature`
- `@spec-0144` A cycle that leaves a role unnamed is refused, `scorer.feature`
- `@spec-0214` A cycle whose verification did not pass is not scored (2 rows), `scorer.feature`
- `@spec-0418` The score of a slice cycle counts the slice's items and the regression items apart, `13-slice-development/scorer.feature`

6 scenarios, verified in cycle 8.

## Components and interfaces it touches

measurement/scorer (`run`, `filed`), measurement/checklist (`read-cycle`,
`derived`).

## Done when

measurement/scorer's unit tests cover each role refusal, ratified.edn
missing, refused and passed, score.edn's keys in spec-0174's order, and a
slice cycle's two tallies; `wasm/test.sh` writes a passing result for each
scenario above; no score.edn is written on any refusal; earlier tickets'
results unchanged.
