# Ticket 05: Verdicts held to one shape, and nothing recorded that was not measured

Slice: `07-measure` (`plan.md`, Scope In: "The scorer: ... verdicts in one
shape, from one or several graders"); `scorer.feature`, Rules "Verdicts
have one shape" and "The instrument refuses to record what it did not
measure"; the glossary's "Verdict". Builds on ticket 04.

## What it delivers

measurement/scorer's verdicts, every file given by `--verdicts` read and
held as one (spec-0379):

- Each file read as EDN: not readable, exit 1 naming it and the line where
  it stops, `FILE:LINE` (spec-0380, row 2; G-23); not a vector, exit 1
  (spec-0213).
- Each verdict a map of `:id`, `:verdict` (`:pass` or `:fail`) and
  `:evidence` (a string): another verdict, or no evidence, exit 1 naming
  the id and what is malformed (spec-0213; G-21); an id graded twice, in
  one file or across files, exit 1 naming it and, across files, both files
  (spec-0213, spec-0380); an id not on the checklist, exit 1 naming it
  (spec-0201).
- No verdict in any file given: exit 2, saying the grading did not happen,
  nothing written (spec-0022; G-18).
- A checklist item no verdict grades: filed as `{:id ID :verdict :fail
  :evidence "ungraded: no verdict was given" :ungraded true}`, counted
  scored and not passed (spec-0023).
- Every exit 1 and 2 writes nothing.

## Scenarios it satisfies

- `@spec-0212` A verdicts file is a vector of id, verdict and evidence, `scorer.feature`
- `@spec-0379` The verdicts of several files are filed as one, `scorer.feature`
- `@spec-0380` Verdicts across files are held to the same rules as one file (2 rows), `scorer.feature`
- `@spec-0213` A malformed verdicts file is refused and nothing is written (4 rows), `scorer.feature`
- `@spec-0022` Zero verdicts records nothing, `scorer.feature`
- `@spec-0023` An item nobody graded is scored as a fail, `scorer.feature`
- `@spec-0201` A verdict for an item not on the checklist is refused, `scorer.feature`

7 scenarios, verified in cycle 8.

## Components and interfaces it touches

measurement/scorer (`run`); the kernel's EDN reading (slice 01's ED-13),
which must name the line where a file stops.

## Done when

measurement/scorer's unit tests cover each row of spec-0213 and spec-0380,
a file with an escape EDN refuses on its third line, an empty vector, an
id across two files, an ungraded item; `wasm/test.sh` writes a passing
result for each scenario above; earlier tickets' results unchanged.
