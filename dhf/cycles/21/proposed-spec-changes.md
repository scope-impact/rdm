# Cycle 21: score and proposed spec changes

spec-25: a core harness, and plugins each project composes (ADR 0003). Scoped
to the components the change from spec-24 reaches, on cycle 20's code. Run by
cycle 19's release (13832de, cut spec-23).

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (5 scopes) | 33.6 | 491,889 | 399 of 399 scenario results and 88 unit tests, twice | 0 |
| graders A, B in parallel (71 items, the jar) | 9.4 | 292,692 | 69 pass, 2 fail | 0 |
| **cycle** | **43.0** | **784,581** | | **0** |

1. **Held-still oracle, scoped** (`--from spec-23 --to spec-25`, the first run
   of the rule): 0 differences outside the scopes; the in-scope cases
   (scopes, verify) skipped and listed.
2. **Verify:** passed, by cycle 19's release (tests 296 scenarios, then
   `answers`).
3. **Score:** **69 of 71**. Grader A (Command, Plugins, Oracle, Lint) 38/40,
   grader B (Verify) 31/31; both say the black box was enough.
4. **Scoreboard 20 to 21:** 56 compared, 1 better (spec-0344: RDM_SPEC now
   starts from a jar, cycle 20's one real fail), 0 worse; 15 new items.
5. **Posterior:** P(some item of the slice is wrong) = 89.4% (90% interval
   59.4% to 100%): two fails on a slice of 71, both one cause, labelled wording.

## The fails

- **spec-0359, spec-0360 (wording):** `plugin add` from the packaged jar carries
  no templates ("the templates it carries are none"). From its sources the
  build copies them; the way of working packages `src/` only, and the spec
  says "the templates rdm-spec carries" without saying a package carries them.
  **Proposed:** the spec says every package of rdm-spec carries the templates
  (`plugins/`), and the way of working packages them (`--classpath src:plugins`
  read as resources, or beside the jar).

## What the scenarios leave unstated (from the graders)

1. **spec-0331:** a mapping finding now names the result's file with no line
   (`FILE: RULE: what`); the scenario still says FILE:LINE. State the form.
2. **spec-0361:** "prints … and exits 1": which stream. Make it "writes … on
   its error stream", as spec-0354 does.
3. **The glossary's Check entry** still says one of three kinds; it is four
   since spec-25 (the integrator's miss in spec-25).
4. **The component label** is the component's name, not its id (raised in
   cycles 03, 20 and 21).
5. **An outline's step names** carry the keyword, with the row's values in.
6. **A @wip scenario without a result** is fine only by implication
   (spec-0208, spec-0314).
7. **SpecParsed's scenarios** carry keys beyond events.edn's :shape (:tags,
   :tag-lines, :levels): the published language should say whether its shape
   is exact or a minimum.
8. **Which comes first:** verify refuses an incomplete configuration before any
   Gate plugin runs; unstated.
9. **"After the core's own stages"** (spec-0352) is visible only as line order.
10. **A subdirectory:** rdm-spec refuses outside a project's root (the glossary
    says so since spec-24; the build does).
