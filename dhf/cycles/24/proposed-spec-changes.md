# Cycle 24: proposed spec changes

Cycle 24 built the whole project from spec-31 (spec-30's slices layout, and
rdm-spec's own spec moved into ten slices). 324 of 325; the one fail
(spec-0364) is the grading room's, as in cycles 22 and 23. Scoreboard 23 to
24: 163 compared, 0 better, 0 worse; 162 new to cycle 24 (a whole-project
cycle). The builder's suite: 444 results and 14 unit tests, twice green.

## What the held-still oracle saw (whole-project: every case in scope, none judged)

The full oracle on the build gave 15 differences. They are information for
the spec, not a verdict:

1. **Tag labels and inherited tags.** verify refuses the oracle's
   steps-match fixture, every result `mapping-differs: tag`: the fixture's
   results carry the need tag the scenario inherits from its feature
   (`UN-001`), which cycles 19 to 23 accepted. spec-0308 states "one per
   Gherkin tag, without its @" and never whether an inherited tag is one.
   Proposal: state it (Allure's own Gherkin adapters include feature tags).
2. **check's standard output** now carries `:paths` and `:wiki`, and its
   findings name the stale `.svg` beside each stale `.mmd`. Neither is
   stated; the first joins the oracle's unstated list as `:knowledge` did.
3. **checklist's output** carries `:clauses 0` outside the regulated
   profile. Unstated.

## Contradictions the spec holds (again)

4. **Exit 2 and ratified.edn.** The glossary: a verify that cannot run writes
   no ratified.edn. spec-0361, spec-0383 and spec-0217 (an unstamped cut,
   `:checks []`) write it. Raised in cycle 23 too; settle it in the glossary.
5. **The Check request's keys:** spec-0362 says exactly four, spec-0391 adds
   `:results`. Raised in cycle 23.

## Slices (spec-30), what the graders saw unstated

6. **A slice-scoped verify** requires results for every scenario, like a
   whole-project one; spec-0389 narrows a spec scope, nothing narrows a
   slice scope. State that a slice scope requires the scenarios of the
   components it reaches (spec-0395's checklist), no more.

## Smaller gaps and defects

7. **Citation lines.** When several needs cite the same string, every
   finding names the line where the string first appears (UN-004's warning
   names line 12, its citation is on 20). State that a citation finding
   names its own line.
8. **The ddd-domain template carries no lint.clj** though its template.edn
   runs `bb dhf/harness/plugins/ddd-domain/lint.clj`: `plugin add
   ddd-domain` gives a plugin that cannot run. halla-health's wallet has a
   lint.clj written for it. Proposal: carry it in plugins/ddd-domain, and a
   scenario that an added template's command exists.
9. **spec-0385:** glossary.md and domain.edn are never read by check;
   "read from its folder" cannot be seen for them (raised in cycle 23).
10. **The context view** lifts component relationships to the system, so one
    person-to-system pair can appear twice with two labels (spec-0198).
11. **spec-0058** (a changed id prefix re-stamps old ids), **spec-0068 vs
    spec-0313** (`unknown`), **spec-0062** (status's exit on an unreadable
    result): raised in cycle 23, still open.

## The way of working

12. **Unit tests:** the builder wrote 14 (cycle 23's code had 77). Test-first
    shows in its failing runs per scope, but the unit layer is thin. The
    brief asks for one unit test before the code it tests; say how many.
13. **Grader b read outside its room** (a failed cd, then tail on two of
    rdm's generated views). No write; recorded in runs.edn.
14. **The oracle's goldens move when a release is installed** (step 8):
    skipped for cycle 23, which stopped cycle 24's fence.
