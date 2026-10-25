# Cycle 09: score and proposed spec changes

Cycle 08's findings folded in (spec-12), then, mid-cycle, the oracle moved
out of the repository on the spec owner's decision (spec-13): one
`oracle.edn` in one artifact, pinned by digest.

1. **Scopes:** `rdm-spec scopes spec-11 spec-12` listed seven components; the
   person added kernel/command and spec/check, which the README's new rules
   reach. Eight scopes.
2. **Fence:** three rooms from spec-12. The oracle group's builder was
   stopped when the spec owner chose the artifact. Its room was fenced again
   from spec-13. The other two rooms were kept, as spec-12..spec-13 touches
   nothing they read.
3. **Build:** three builders, all test-first, each naming the scenarios it
   saw fail before writing code.
4. **Merge:** no file touched by two rooms. **Two integration fixes by hand**
   (below). Afterwards: 288 of 288 scenario results and 172 unit tests,
   twice.
5. **The new oracle on this repository:**
   - Read from the local artifact layout by digest, it compares all eight
     goldens and passes.
   - Without the layout it reaches for the registry, is denied, and refuses
     (exit 2): no silent pass.
   - Cycle 08's build refused this repository's own oracle.
6. **Ratify:** passed against spec-13.
7. **Checklist:** 107 items.
8. **Grading:** three graders. The oracle grader made its own artifacts and
   layouts.
9. **Score:** **107 of 107.**
10. **Scoreboard 08 → 09:**
    - 95 items compared: **0 better, 0 worse**
    - 12 items newly in scope, counted apart
    - 27 items out of scope, not compared, said out loud

## Integration fixes, and why

1. **Fixtures inherited the room's oracle.** Fixtures copied from the room
   carried its `:oracle` (spec-13), so the gate in 22 fence, ratify and
   scorer fixtures reached for the registry. `copy-of-room!` now strips it:
   a fixture is a project the scenario makes. The README should say a
   fixture never carries the room's oracle unless its scenario sets one.
2. **A shared step's ctx keys are not in the spec.** The kernel's
   "a wiki page whose footnote cites ..." names the page `:wiki-file`; the
   site's Then read `:wiki-rel`. The README should say that the ctx keys a
   shared step sets are named in the kernel's step file, and that another
   file reads only those.

## Found by grading that could mislead an auditor (no Then checks it)

3. **The audit copy's traceability page reads uncommitted scenario edits,**
   while the manifest says `:clean true`. `:clean` looks only at the clause
   lists' and documents' directories. Make the whole audit copy at HEAD, the
   spec and the results included, and compute `:clean` over everything it
   read.
4. **Release and audit read different revisions.** Release judges clauses
   in the working tree, audit at HEAD. An untracked document closed a gap
   for release (exit 0) and not for audit (exit 1). Say which revision each
   reads, or make both read one.
5. **`oracle record` writes the new digest into the configuration but does
   not commit it.** The glossary says "committed"; spec-0230 does not. Say
   who commits.

## Builders' and graders' notes, to settle

6. **A list item's continuation paragraph** (four spaces after a blank line)
   is read as code, so a tag there names nothing. That follows the
   glossary's words, not Markdown's. Say "an indented block not inside a
   list".
7. **A tag over two lines leaves a leading space** on the second line of the
   engineer's copy. Reported four cycles running; say whether it matters.
8. **An include cycle in a clause list:** cycle 08 read it once, cycle 09
   makes it a finding (`clause-include-cycle`). Pick one.
9. **The init hook calls whatever `rdm-spec` is on PATH,** so a commit in a
   grader's fixture ran the installed release, not the build being graded
   (no verdict used it). Say which `rdm-spec` a hook runs, or have graders
   and builders commit in fixtures with the hook off.
10. **Wording:** spec-0100 says "no file under dhf/spec changes" where the
    kernel's step says "no file in the spec changes". Unify.
11. **Rule names builders invented:**
    - oracle: `oracle-no-cycle`, `oracle-malformed`, `golden-differs`,
      `golden-orphan`, `golden-unproduced`, `instrument-failed`
    - compliance: `clause-line-malformed`, `document-id-duplicate`
    - release: `scenario-planned`, `scenario-failed`, `scenario-unverified`

    Fine under the README; pin the ones another context reads.
12. **A component's design prose keeps its own H1 under "## Design":** two
    H1s on the page. Cosmetic, reported three times.

## Process

- **The oracle is converted and stored:** `oracle.edn` at
  `sha256:cf29099c…` in the local layout `/home/user/oci/rdm-spec-oracle`.
  The registry push waits for a token. The folder `dhf/harness/oracle/`
  stays until a release that reads the artifact is installed.
- **Fixtures:** with the tests' temporary directory isolated, the merged
  suite left nothing behind. Builders still leave directories in the shared
  /tmp from their own runs.
- **Breaches:**
  - The compliance builder ran once without RESULTS_DIR and wrote results
    into its room (deleted).
  - The oracle builder wrote debug output to fixed /tmp paths.
