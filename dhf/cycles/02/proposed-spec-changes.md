# Cycle 02: score and proposed spec changes

The first cycle run through the installed rdm-spec (cycle 01's accepted build):

1. **Cut:** spec-2, tagged locally. Its views were stale under its own hash
   definition, so it was cut again as spec-3 once the views were redrawn.
2. **Fence:** eight scopes, each fenced by `rdm-spec fence` into its own clean
   room as a single root commit: kernel/bus, architecture/views, publishing,
   knowledge, regeneration/fence, project/init, measurement, ratification.
3. **Build:** eight builders, each passing its own scenarios.
4. **Merge:** three integration fixes by hand (below). The suite passed twice:
   213 of 213 scenario results and 130 unit tests.
5. **Ratify:** `rdm-spec ratify` passed against spec-3.
6. **Checklist:** `rdm-spec checklist` gave 180 items, derived for :scope
   :project, because the installed release takes one scope.
7. **Grading:** four graders, none of them a builder. Each set up its own
   fixtures and ran the ratified build; none read the builders' tests or results.
8. **Score:** 179 of 180 pass, filed by `rdm-spec score`.
9. **Scoreboard:** cycle 01 is a bootstrap, so nothing was compared; cycle 02
   is the first baseline.

## The fail

- **spec-0173:** the checklist must hold the items of the cycle's scope; the
  build writes every item, whatever the scope. The measurement builder chose
  "whole spec" to settle a contradiction (finding 1 below), its own tests
  encoded that choice, and ratify passed. Only the grader, reading the
  scenario, caught it.

## A. Wrong or contradictory: fix first

1. **What does a scoped checklist hold?** spec-0173 and the glossary say the
   items of the scope. The scoreboard's "every other scenario" (spec-0172 and
   the ADR) needs the rest graded too, and the scorer refuses verdicts for
   items not on the checklist. Decide which. If the checklist holds only the
   scope, the scoreboard's "rest" can only be compared in a project-scope cycle.
2. **A cut can hold stale views.** spec-2 failed its own freshness rule. Make
   the cut refuse a HEAD whose `rdm-spec check` fails, or have the spec say
   views are drawn before a spec change is committed.
3. **Fence's usage allows one scope; the glossary allows a list.** Builders
   accepted comma lists; pin the form (spec-0222's neighbours).
4. **The model hash leaves sets unordered.** The hash is "printed back with
   every map's keys sorted"; it should also say how sets and key types order.
   Two builders happened to agree this time.

## B. Contracts to state

5. **KnowledgeIndexed citations** carry only the string. The site re-parses
   `id:path@commit`. Say so, or add `:source :path :commit` to the shape.
6. **The checklist reads the parser's state, not SpecParsed** (no subscriber).
   The model draws an event.
7. **Shared fixture helpers:** `cut!` in the measurement steps was used by
   ratify's steps with a different assumption (a git repository). One step
   text, one meaning should extend to shared helpers, or each context keeps
   its own.
8. **Scenario layout of shared Givens:** "a wiki page whose footnote cites X"
   is defined once (site) and used by knowledge, under different keys.

## C. Defects no Then catches (graders)

9. **FILE:LINE format:** the a-scenario-owner (spec-0014) and unreadable-result
   (spec-0062) findings leave out LINE, and spec-0062's finding spans two
   lines, against "one line per finding".
10. **Blank parse message:** an unmatched closing brace in the model prints
    "the  opened on line  is never closed", with the delimiter and line blank.
11. **Restamping:** `stamp` restamps `@spec-` tags under a new id prefix. Not
    covered; say whether a prefix change restamps.
12. **Underspecified Givens:** spec-0220 (cycle scope), spec-0221 (publishers),
    spec-0074 (rdm-spec's model already has an id :spec).

## D. Process

- **The installed rdm-spec on PATH** can shadow the build being graded; one
  grader caught a slip. Graders should run the build by path.
- **Graders and builders wrote outside their directories** twice (scratchpad,
  /tmp). Nothing was lost. Fence by allow-list where possible.
- **No list scopes yet.** The installed release takes one scope per cycle, so
  the checklist was derived for the project.
