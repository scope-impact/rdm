# Cycle 10: score and proposed spec changes

Cycle 09's findings folded in (spec-14). The first cycle run by cycle 09's
release, whose oracle gate reads the artifact by digest.

1. **Scopes:** `rdm-spec scopes spec-13 spec-14` listed the compliance
   context, measurement/oracle and publishing/site. The person added
   kernel/command, which the README's two new rules reach.
2. **Fence:** the artifact gate passed. Two rooms, each one root commit with
   no oracle content.
3. **Build:** two builders, both test-first, each naming the scenarios it saw
   fail first.
4. **Merge:** **no integration fix by hand**, the first time since cycle 05.
   292 of 292 scenario results and 179 unit tests, twice. The rebuilt oracle
   compares this repository's eight goldens by digest.
5. **Ratify:** passed against spec-14.
6. **Checklist:** 62 items.
7. **Grading:** two graders.
8. **Score:** **62 of 62.**
9. **Scoreboard 09 → 10:**
   - 61 items compared: **0 better, 0 worse**
   - 1 item newly in scope, counted apart
   - 46 items out of scope, not compared, said out loud

## Found by grading that could mislead an auditor (no Then checks it)

1. **The audit does not check the configuration for uncommitted changes.**
   Removing a declared clause list in the working tree only let `audit` pass
   and write a manifest pinned to a commit the release check refuses.
   Add `dhf/harness/config.edn` to what the audit copy is made from (glossary
   "Audit copy"), and a row to spec-0274.

## Builders' notes, to settle

2. **A dotted descendant names every ancestor:** `STD:5.1.a` names `STD:5`
   too, as innolitics-rdm matched. The glossary names only the parent; say
   every ancestor.
3. **Any key-like line in a clause list becomes a clause,** so a stray prose
   line ("Quality system") becomes the clause "Quality". It makes a false
   gap, never hides one. Say what a key looks like.
4. **A tag over two lines leaves a leading space** on the engineer's second
   line. Reported five cycles running and read as passing each time: write
   it into the glossary as allowed.
5. **The no-oracle case** reports on standard output only, with no warning,
   so the gates stay quiet. Say so.
6. **Shared step texts still outside the kernel:**
   - `architecture_views`: the stale view; sets `:stale-view`
   - `measurement_cycle`: the cycle's cut and the unstamped scenario;
     `ratification_ratify` reads `:unstamped`
   - `kernel_runner`: the outline with three rows

   Also dead conditional definitions: `knowledge_wiki`,
   `verification_status`, `spec_support/ensure-shared-steps!`. All wait for
   their components' next rebuild.

## Process

- **Babashka ignores `JAVA_TOOL_OPTIONS`.** The integrator's "isolated
  temporary directory" in cycle 09 isolated nothing, and its "nothing left
  behind" was vacuous. Measured properly now, a full run leaves 13
  directories, all from the unit tests of cut, parser, ratify and runner
  (outside this cycle's scopes). Before this cycle it was about 425 a run.
- **A stopgap in the kernel:** its step file reroutes `support.clj`'s
  `tmp-dir` at run time, changing another file's behaviour without editing
  it. Replace it with the change itself when `support.clj` is next in scope.
- **No breach this cycle.** Both builders and both graders committed in
  fixtures with the hook off.
