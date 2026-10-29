---
name: harness
description: Work one phase of a regeneration cycle under the clean-room harness (plan, build, grade, review, integrate). Use when asked to plan a slice or state spec gaps, to build a cut in a room, to grade a build black box, to review a room's diff for invented rules, or to run a cycle's machinery (cut, scopes, fence, verify, score, scoreboard).
---

# Harness

One cycle regenerates a product from a cut of its spec, in a room, under a
gate the builder does not control, measured by an instrument the builder
never sees. You work **one phase** of it: `plan`, `build`, `grade`, `review`
or `integrate`. The phase is given with the task; if it is not, ask for it
before reading anything else, because each phase forbids what another
requires.

## Orient, before any phase

Read, in this order, and write one line per file saying what it settled:

1. `README.md`: the intent and the one thing.
2. `docs/wow/invariants.md`, `docs/wow/way-of-working.md`,
   `docs/wow/harness.md`: the rules, the cycle, the guardrails.
3. `docs/wiki/glossary.md`: the words. Use them as defined; a word the
   glossary says to avoid is a finding at review.
4. For a product: its `spec/` at the cut named in the task (storm, model,
   feature files, `placeholders.toml`), and the plan being worked.

Complete when every file above is read at the cut, not from memory, and
the line-per-file summary names the cut's tag.

## Guardrails that hold in every phase

- **The spec alone.** No phase learns behaviour from an implementation.
  A room holds no earlier implementation of the scope; `raw/` holds none
  of anything regenerated. A question the spec does not answer is a **gap**
  (plan) or a **decision** (build), never a guess read from code.
- **Stay in your room.** Write only where the phase's file says. A stray
  file outside the room is a finding against you, not a tidy-up for
  someone else.
- **One format.** Everything you write is TOML or Gherkin; every task
  prints one TOML document and exits 0 (passed), 1 (refused), 2
  (cannot run). Read the exit code the same way the harness does.
- **The owner decides.** Accepting a plan, a gap, a verdict or a cycle is a
  person's. Write the recommendation and stop; "accepted as recommended"
  is not yours to write.
- **Words.** The glossary's names, re-cased per layer. No stack word in
  anything under `spec/` or `docs/`.

## Phases

Read only the file for your phase; the others describe work you must not
do in this round.

| Phase | Who you are | Read |
|---|---|---|
| `plan` | the planner: a plan and its tickets from the spec alone, every spec gap named visible or free | [phases/plan.md](phases/plan.md) |
| `build` | the one builder: test-first in the room until the suite is green and writes results | [phases/build.md](phases/build.md) |
| `grade` | a grader: black box, one verdict per item with evidence | [phases/grade.md](phases/grade.md) |
| `review` | the reviewer: invented rules and spec drift in the diff; never grades, never fixes | [phases/review.md](phases/review.md) |
| `integrate` | the integrator: the machinery in order, the records filed, no owner's decision made | [phases/integrate.md](phases/integrate.md) |

## Report

Every phase ends with a report under thirty lines: what was done, by
ticket or item; every decision or gap with its pruned branch; every
refusal the harness printed, verbatim; what waits on the owner. Nothing
else.
