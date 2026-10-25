# Cycle 05: score and proposed spec changes

The first cycle run end to end by an accepted, current release (cycle 04's
build), and the first whose builders had the Löyly page.

1. **Scopes:** taken from the spec diff between spec-7 and spec-8: seven
   components whose feature files changed.
2. **Fence:** `rdm-spec fence` built seven rooms, its oracle gate passing (no
   oracle recorded yet).
3. **Build:** seven builders. Each cited the Löyly rule it followed for an
   ambiguous choice, or said Löyly was silent.
4. **Merge:** **no integration fix by hand**, the first time. 230 of 230
   scenario results and 185 unit tests, twice.
5. **Ratify:** `rdm-spec ratify` passed against spec-8 (full room-tree hash).
6. **Checklist:** `rdm-spec checklist 05` directly: 74 items, the cycle's
   scopes.
7. **Grading:** two graders in their own grading rooms, with the Löyly page.
8. **Score:** **74 of 74** pass.
9. **Scoreboard 04 → 05:**
   - 64 items compared: **0 better, 0 worse**
   - 10 items newly in scope, counted apart
   - 38 items out of scope, not compared, said out loud

## Found by grading (no Then checks it)

1. **Wiki pages are orphans.** They are built to HTML with working footnote
   links, but they are not in the site's navigation and nothing links to
   them, against UN-010 ("one published site"). spec-0146 fixes the
   navigation and says nothing of the wiki; spec-0133 asks only that a page is
   "published". Add a Then: the wiki is reachable from the home page.
   Builders read this three ways in three cycles.
2. **`scopes` prints warnings as `FILE: warning: RULE: what`,** a form the
   README does not define. Add it: a warning about a file with no line.
3. **The home page records the absolute path** of the results directory.
   Same inputs still give the same bytes (spec-0101), but a path from one
   machine is noise on another. Say a page names paths relative to the
   project.

## Builders' open choices, to settle

4. **`citation-malformed`:** a need's source that is not `id:path@commit`.
   Sources made it an error; the spec is silent.
5. **What `scopes` counts as shared:** allow-listed files no component owns,
   reported as warnings and exit 0; design prose `.md` counts as its
   component's.
6. **A missing ledger** counts as 0 issued; an unreadable one is a finding
   (exit 1), while the README's exit 2 covers "a missing file". Pin which.
7. **Gate findings:** only errors stop the task, warnings pass. Say so.
8. **spec-0219 still names `/tmp/r1`,** a fixed path against the README's
   ROOM rule. Reword.
9. **spec-0236's Given** has no git history, so "no dropped pin" is only
   checked by a unit test. Give it a history.
10. **spec-0101's "run twice"** overwrites the first site; let the shared
    step keep a snapshot.

## Process

- **One builder (kernel/command) was not test-first.**
- **Two builders wrote one file each outside their mktemp directory,**
  moved or removed. None wrote under `dhf/`; graders stayed in their rooms.
- **The oracle still has no recorded goldens for rdm-spec itself.** The gate
  passes because there is nothing to judge. Recording them is the next step
  for the instrument.
