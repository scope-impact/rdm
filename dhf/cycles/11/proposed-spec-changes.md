# Cycle 11: score and proposed spec changes

Cycle 10's findings folded in (spec-15), and the **whole project regenerated**:
every implementation file rebuilt from the spec, the first time since
cycle 02.

1. **Built in partitions.**
   - Phase 1 rebuilt the kernel and the test scaffolding no context owns
     (`test.clj`, `bb.edn`, `support.clj`).
   - Phase 2 ran five builders in parallel, each in a room fenced for its
     group of contexts, with phase 1's files copied in.
   - The merge took every context from the room that rebuilt it: 115
     implementation files, none identical to the cut's.
2. **Every builder was test-first for its scenarios.** Each saw them fail
   (stage not built) before writing code. Most wrote their unit tests after
   the code.
3. **Merge:** **no integration fix by hand.** 294 of 294 scenario results and
   119 unit tests, twice. No shadowed shared step, and no fixture left in
   /tmp (counted before and after).
4. **The rebuilt oracle on this repository's artifact: 25 differences**
   (below). The installed release's oracle passes. Ratify, run by the
   installed release, passed against spec-15.
5. **Checklist:** 238 items, the whole spec.
6. **Grading:** four graders.
7. **Score:** **237 of 238.**
8. **Scoreboard 10 → 11:**
   - 62 items compared: **0 better, 1 worse (spec-0274)**
   - 176 items of cycle 11 not scored in cycle 10, not compared, said out
     loud

   The worse item is a sharper probe as much as a regression: cycle 10's
   grader never tried an ignored file.

## The oracle caught instrument drift: this build must not be installed

1. **The scorer's and the scoreboard's output shapes are pinned only by the
   goldens.** Both were rebuilt for the first time since the release that
   recorded those goldens (cycle 04/05), and the rebuild produces another
   shape:
   - the ungraded item's `:evidence` and `:ungraded` in `score.edn`
   - the scoreboard's whole result (`:target`, `:rest`, `:statements` and
     their item lists, against a new `:better {:count :items}`,
     `:denominator`, `:says`)

   No scenario checks either shape, so the builder could not know. Add
   contract scenarios for `score.edn`'s ungraded item and for the
   scoreboard's result, matching the recorded goldens. Re-recording the
   goldens instead would erase the evidence that anything moved, and the
   recorded shape is what every cycle's tooling reads. Until then, this
   build's gate refuses fence and ratify on this repository; cycle 10 stays
   the release.

## The fail, which could mislead an auditor

2. **spec-0274: an ignored document is not refused.** A document git ignores
   (`.git/info/exclude`) is copied into the audit copy and closed a clause
   that the release check refuses at HEAD. Meanwhile the manifest pins HEAD.
   The build asks `git status`, which skips ignored files; the spec says
   "differs from HEAD, untracked included". Say ignored files count: every
   file the audit reads must be HEAD's (compare against HEAD's tree), and
   add the row.

## Found by builders and graders (no Then checks it)

3. **A partly run outline counts as verified:** fewer results than rows, all
   passing. Say every row needs its own passing result. A release could
   otherwise pass on rows never run.
4. **The kernel's "no file in the spec changes" never checks the command
   ran:** spec-0100 passed while publish exited 2. A Then about what did not
   change must also check that the command passed.
5. **The kernel's "a wiki page whose footnote cites C" always makes a new
   copy,** throwing away an earlier Given's project (spec-0236 works around
   it). It should build on the scenario's project when there is one.
6. **SpecParsed carries keys events.edn does not list:** `:name`, `:keyword`,
   `:background`, `:examples`, and `:rule` as the rule's name. Status,
   trace, audit and site read them. Add them to the shape.
7. **A project's own non-empty event catalogue that does not declare
   rdm-spec's internal events cannot run check** (exit 2). Say whether a
   project's catalogue is its own events only.
8. **A stale `containers.svg` alone** gives a finding naming
   `containers.mmd:1`, the wrong file.
9. **Result shapes with no contract scenario:**
   - status's `{:results :counts :scenarios ...}`
   - scopes' `{:scopes :shared :from :to}`
   - ratified.edn's keys and how its room-tree hash H is computed

   Pin those another context reads.
10. **Step files reaching into other contexts:** spec and ratification step
    files required measurement's step helpers before this cycle. Check
    whether the rebuilt ones still do; the README says a scope's steps are
    its own.

## Process

- **Breaches:**
  - A grader's unset shell variable made `/layout` and `/op` at the
    filesystem root (cycle 06's `/o` and `/e` are still there too).
    Removing them needs a person.
  - Three builders each copied a log once outside their room (deleted).
- **Fixtures:** phase 1's scaffolding left no fixture behind. The
  integrator removed about 5,000 old unit-test directories from earlier
  cycles.
