# Cycle 13: score and proposed spec changes

spec-17: one builder, Löyly's fence made robust, dependency scopes, agency
observed. The first cycle built by one builder in one room, and the first
whose runs are observed and recorded (`runs.edn`).

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (12 scopes) | 49.8 | 603,427 | 312 of 312 scenario results, each scope seen failing first | 1: a scratch directory left in /tmp |
| grader A (54 items) | 10.2 | 162,148 | 54 pass | 0 |
| grader B (53 items) | 9.0 | 185,994 | 53 pass | 0 |
| **cycle** | **69.0** | **951,569** | | **1** |

The graders were observed together, with one snapshot for both. Reads are not
observed. The integrator's own run is not observed.

1. **Scopes:** `rdm-spec scopes spec-16 spec-17` (6), plus spec-17's event
   rule applied by hand (the installed release predates it): `SpecParsed`'s
   publisher and its five subscribers, 12 in all, in build order.
2. **Build:** one builder, sequentially. **No integration fix**: with one
   room there is nothing to merge.
3. **Integrator's checks:**
   - Suite: 312 of 312 and 127 unit tests, twice, with no fixture left.
   - Oracle on the real artifact: 0 differences.
   - The new path classification passes on this repository.
4. **Ratify:** passed against spec-17, run by the installed cycle 10 release
   through its allow-list (`:allow`, kept for it).
5. **Score:** **107 of 107.**
6. **Scoreboard 11 → 13** (cycle 12 was never graded):
   - 90 compared: **1 better (spec-0274), 0 worse**
   - 17 new items, counted apart
   - 148 out of scope, said out loud

## For spec-18

1. **The read-by rule** (spec owner's decision). `events.edn` names, per key,
   the subscribers that read it. An event change reaches its publisher and only
   the subscribers that read a changed key. This cycle would have been 7
   scopes, not 12: about 20 minutes and 300,000 tokens less.
2. **A result counts toward an outline only if it names its row**
   (grader B). Three results carrying no row parameters verified a three-row
   outline: a release could pass on re-runs of one row. Matching with
   parameters is correct.
3. **spec-0086's kept paths omit `.gitignore`, `.githooks` and `.github`,** so
   an initialised project would fail its own classification from its second
   commit. The builder added them; pin them in the row.
4. **Every unbuilt stage refuses (exit 2), not only a gate** (builder,
   following Löyly's "a missing tool turned its command into a no-op"). Write
   it into the glossary.
5. **A project's own event catalogue:** a project whose `events.edn` is
   non-empty must also declare rdm-spec's internal events, or check exits 2.
   Say whether a project's catalogue holds only its own events.
6. **Pin what is still loose:**
   - status's result
   - the scoreboard's bootstrap and moved-denominator forms (the builder's
     own wording)
   - `observe`'s pending-runs file (`observing.edn`)
   - that breaches count runs, not items
7. **Minor:**
   - A stale `.svg` alone is reported as its `.mmd` (third time).
   - A lint message repeats the event's name.
   - A component's prose keeps its own top-level heading (fourth time).
   - The built HTML differs between runs; spec-0101 pins only the Markdown and
     the configuration.
   - `scopes.feature`'s comment still says "allow-list".

## Before `:allow` can go

- **`support.clj`'s `copy-of-room!`** still copies `:clean-room :allow`; it
  must copy what `:keep` names first.
- **After that,** install a spec-17 build, remove `:allow` from the
  configuration, and cycle 14 is the first fenced by the deny-list itself.
