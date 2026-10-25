# Cycle 07: score and proposed spec changes

The first cycle of the regulated profile (spec-10): a new bounded context,
compliance, with gap analysis as a stage of check and the `audit` task, plus
gaps in the release check and clause items in the checklist.

1. **Scopes:** `rdm-spec scopes spec-9 spec-10`: the compliance context,
   kernel/command, measurement/checklist, verification/release.
2. **Fence:** the oracle gate passed, then one room for every scope. The
   compliance context had no code, and the other three scopes run it.
3. **Build:** one builder. It was not test-first for the kernel: it worked out
   the kernel's shape from its callers before writing steps.
4. **Merge:** **one integration fix by hand** (below). Afterwards: 272 of 272
   scenario results and 179 unit tests, twice.
5. **Ratify:** passed against spec-10.
6. **Checklist:** 41 items: 24 compliance, 17 command, release and checklist.
7. **Grading:** two graders in their own grading rooms. The compliance grader
   built every fixture with the build's own `init`.
8. **Score:** **40 of 41.**
9. **Scoreboard 06 → 07:**
   - 8 items compared: **0 better, 0 worse**
   - 33 items newly in scope, counted apart
   - 63 items out of scope, not compared, said out loud

## The fail

1. **spec-0250: the clauses page does not keep includes in place.** It groups
   clauses by the file that holds each line, so an included list gets its own
   heading, a list the project never declared. The page's own text still
   says "includes in place", which would mislead an auditor. The Given has no
   include; the grader added one because the Then names includes. Give the
   Given an include, and say that clauses are grouped by declared list only.

## Integration fix, and the rule it repeats

2. **spec-0239's Given counted the room's own wiki pages.** spec-10 added a
   page, so the count broke. Its fixture now makes its own index of two
   pages. This is the second cycle running to need this fix (cycle 06,
   spec-0231). Write it into the README: a scenario's project is one the
   scenario makes, never the room or repository the tests run in.

## Found by grading (no Then checks it)

3. **PDFs are not reproducible:** the same commit gives a different PDF each
   run. spec-0254 checks only the Markdown. Say whether a PDF must be
   reproducible. An auditor comparing manifest hashes would see them differ.
4. **`:clean` is false because of the audit copy itself** when OUT is inside
   the project, and untracked files of any kind count. Say whether `:clean`
   means "no tracked file changed", or refuse an OUT inside the project.
5. **The engineer's copy of a tag split across two lines** leaves a leading
   space on the second line. Harmless in Markdown.
6. **spec-0250 puts a tag on "line 3"** of a document, which can only fall
   inside its frontmatter. Tags there count, and the engineer's copy strips
   them from the title. Say whether frontmatter is a place a clause is met;
   if not, move the line.
7. **spec-0266 does not say which cycle scopes carry clause items.** The
   builder carries them under `:project`, or a scope naming the compliance
   context. Say so.
8. **`init /absolute/path`** exits 2 with a leaked exception message. Say
   whether PATH may be absolute; either way, refuse with a reason.
9. **The existing-directory refusal reads `OUT OUT already exists`.**

## Builders' open choices, to settle

10. **Every task but `help` and `init` reads the configuration** when one is
    present. A missing required value is refused by the stage that needs it.
11. **Quiet stages:** the gap stage runs quietly (publishing its event only)
    in checklist, release and audit, as sources and spec check do in
    publish. The gates are the oracle, the views' freshness and, before
    audit, the release check. Write the task table's stages and gates into
    the spec.
12. **The release check relays only gaps from ClausesIndexed;** document
    findings (no id, duplicate id) stop check, not release. Say which.
13. **The manifest's shape:** `{:commit :clean :files [{:path :sha256}]}`.
    Pin it in the scenario.
14. **A shared step text served by the wrong owner:** fence's "it exits 2
    and names (dhf/\S+)" also runs for gap's spec-0258. Make it the kernel's.

## Process

- **Unit tests of runner, ratify, init and fence leave fixture directories in
  /tmp.** The integrator cleared 8,934 (about 4.4 GB). Say that a suite
  removes what it makes.
- **No breach this cycle:** the builder and both graders stayed in their
  rooms.
