# Cycle 03: score and proposed spec changes

The second cycle run by the installed rdm-spec (cycle 01's build).

1. **Cut:** spec-4, tagged locally. It changed the model hash to sort sets
   too, so the committed views went stale, and it was cut again as spec-5
   once the rebuilt views code had redrawn them (cut after the merged
   build's `check` passed, as spec-0224 now requires).
2. **Fence:** nine scopes, each fenced by `rdm-spec fence` into its own room:
   measurement, regeneration, architecture, publishing, knowledge/sources,
   verification/results, kernel/command, spec/parser, kernel/bus.
3. **Build:** nine builders, each passing its own scenarios.
4. **Merge:** five integration fixes by hand (below). The suite passed twice:
   217 of 217 scenario results and 154 unit tests.
5. **Ratify:** `rdm-spec ratify` passed against spec-5, with 184 scenarios.
6. **Checklist:** 105 items, the nine scopes only, as the glossary now
   requires. The installed release predates that rule and lists every item, so
   its project-scope output was kept to the scopes by context and component
   name.
7. **Grading:** three graders, none of them a builder, ran the ratified build
   by path.
8. **Score:** **103 of 105** pass.
9. **Scoreboard 02 → 03:**
   - the denominator moved from 180 to 105, and that was said out loud
   - 101 items compared: 1 better (spec-0173), 2 worse (spec-0098, spec-0172)
   - 4 new items, all passing
   - 79 items out of this cycle's scope, counted apart

## The two fails

- **spec-0098, a regression.** Every published page must say no status was
  derived when no results were given; the wiki index does not. Cycle 02's
  builder read "every page" as including the wiki and passed; this cycle's
  builder read it as spec pages only. The scenario said "every page" both
  times; the grader held to it.
- **spec-0172, a spec defect from spec-4.** Its Given, a verification-scoped
  cycle that scored every scenario, cannot be produced, because a scoped
  checklist holds only its scope (glossary) and the scorer refuses verdicts
  off the checklist (spec-0201). Reword it to two cycles that scored the whole
  project, or drop it in favour of spec-0225. The behaviour is right when
  forced by hand.

## Integration fixes by hand

Each one is a step file depending on another scope's step code:

1. Publishing's steps called `run-cli` from the kernel's step file; the
   rebuilt kernel step file no longer had it. It is now copied into
   publishing's steps.
2. The parser's steps called `read-checklist` from the measurement steps;
   the same fix.
3. The parser and measurement builders both defined "cycle.edn names the cut
   spec-3 and the scope S"; the measurement steps keep the only definition.
4. Publishing's Given put the results directory under `:env-results-dir`; the
   rebuilt kernel's shared When reads a scenario's environment from `:env`.
5. The views were redrawn and the spec cut again (spec-5), because the hash
   changed.

**Proposal:** a step file may use only the kernel's documented shared steps
and `steps.support`. Any other helper is copied in, and every shared step
text is defined once, in the kernel or in the context that owns it. Write it
into the README conventions.

## A. Wrong or contradictory

1. **spec-0172:** see above.
2. **spec-0146:** its Given names no kernel, but its Then expects a kernel
   section; spec-0092 says no kernel means no kernel section.
3. **The model hash's printed form is still not pinned exactly.** The views
   builder sorts by printed form, while the site builder compares first and
   falls back to printed form. They agree on this model and could diverge
   on namespaced keys. Pin it: `pr-str` of the value with maps and sets
   sorted by their elements' `pr-str`.
4. **A hash change makes every cut stale until a build implements it.** Say
   it: a spec change that changes a hash definition is cut twice, before and
   after the views are redrawn.

## B. Defects no Then catches (graders)

5. **FILE:LINE broken in several places:** findings `cut` relays carry a
   leading `: `; score findings, and the unknown-result and unreadable-result
   lines, lack LINE. Add a convention scenario that checks every task's
   stderr line format.
6. **Stale HTML after a failed site build:** the site builder writes `site/`
   before its strict abort, and the HTML stays, although publish says none was
   published.
7. **`{}` on standard output** for refusals that exit 1 (checklist, score,
   cut). Say whether a refused task prints a result.
8. **A dropped pin is caught only before it is committed** (spec-0121 "at
   HEAD"). "A pin is never dropped" needs history, or a ledger of pins.
9. **`init`'s hook runs whatever `rdm-spec` is on PATH,** so it can run a
   different release than the project's. Say which rdm-spec a hook runs.
10. **An id above the ledger draws no finding.**
11. **Fresh `init` can't be fenced or ratified** until views are drawn and
    `:implementation` is declared. Say so, or have init write both.

## C. The instrument (compared with Löyly)

12. **No oracle for the instrument.** Löyly checks its checklist and
    scoreboard tools against recorded goldens and refuses to fence or ratify
    while they disagree. rdm-spec regenerates its own instrument each cycle
    and has no such check. The installed release judging (not the build) is
    the only guard. Proposal: an `oracle` task with recorded goldens for
    checklist, score and scoreboard, run before fence and ratify.

## D. Process

- **Two graders read outside their bounds.** One cloned the real source
  checkouts under /home/user; one used a wrapper because the installed
  release shadows the build on PATH. Nothing was changed. Graders need a room
  of their own with the checkouts they may read.
- **The installed release is two cycles old.** It predates the model hash,
  scoped checklists and list scopes, so the integrator worked around it
  twice. Installing a reviewed newer build is the spec owner's call.
