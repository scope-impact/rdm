# Cycle 08: score and proposed spec changes

Cycles 06 and 07's findings folded in (spec-11): the kernel owns every step
text shared between contexts, a scenario's project is one it makes, and the
regulated profile's open decisions were settled.

1. **Scopes:** `rdm-spec scopes spec-10 spec-11` listed nine components. The
   person added kernel/command and verification/trace (the README's
   shared-step rule reaches them), then project/adopt after phase 1 (it lays
   down init's skeleton). Eleven scopes, the largest cycle yet.
2. **Fence:** the oracle gate passed, then rooms built in two phases. First
   the kernel alone; then four rooms, each with the rebuilt kernel copied in:
   - compliance
   - sources, check, init and adopt
   - checklist, oracle and fence
   - site and trace
3. **Build:** five builders. Two (compliance, measurement) wrote code before
   step definitions and checked their steps afterwards by breaking the code.
4. **Merge:** the rooms touched no file in common but the kernel's, which was
   identical in each. **One integration fix by hand** (below). Afterwards:
   279 of 279 scenario results and 170 unit tests, twice.
5. **Ratify:** passed against spec-11.
6. **Checklist:** 122 items.
7. **Grading:** three graders in their own grading rooms.
8. **Score:** **122 of 122.**
9. **Scoreboard 07 → 08:**
   - 36 items compared: **1 better (spec-0250), 0 worse**
   - 86 items newly in scope, counted apart
   - 5 items out of scope, not compared, said out loud

## The finding that blocks a release of this build

1. **The oracle cannot read rdm-spec's own oracle directory.** The spec never
   says how `dhf/harness/oracle/` is laid out, and the oracle is kept out of
   every clean room, so the builder invented a layout (one directory per
   fixture cycle, its project and goldens inside). On this repository the new
   oracle exits 1 with three `fixture-malformed` findings, where the installed
   release compares eight goldens and passes. As the gate before fence and
   ratify, it would refuse both.
   - The builder's own tests passed only because their fixtures used its
     invented layout.
   - The integrator and a grader each found this independently.

   Pin the layout already recorded:
   - `project/`: one fixture project
   - `cycles/NN/`: `cycle.edn`, `ratified.edn`, `verdicts.edn`
   - `goldens/NN/`: `checklist.json`, `score.edn`
   - `goldens/scoreboard-NN-MM.edn` and `RECORDED.md`

   Also say what a directory with no fixture is: today it passes with a
   warning, so as a gate it would judge nothing.

   **This build must not be installed as the release until then.**

## Found by grading that could mislead an auditor (no Then checks it)

2. **An untracked document counts.** A controlled document never committed
   closes a gap and enters the audit copy, while the manifest says
   `:clean true` at HEAD, a commit that does not hold it. spec-11's
   "untracked files never count" allows it. Read clause lists and documents
   as committed at HEAD. Alternatively, count an untracked file under either
   as not clean.
3. **An indented code block is not code.** A tag inside a four-space block
   names a clause, and the engineer's copy edits the code. Say in the
   glossary that code is fenced, indented or inline, and add a row to
   spec-0260 and spec-0247.
4. **Tags in frontmatter stay in the engineer's copy:** its Markdown and PDF
   still show `[[`. Strip them there too; they name nothing.

## Integration fix, and why

5. **spec-0047's step read trace's scenarios at the top level;** the rebuilt
   trace nests its result under `:trace`. Two builders, each against the
   other's code as it was. Say each task's result shape in its feature (a
   contract scenario), as the checklist does.

## Builders' and graders' notes, to settle

6. **Shared step texts the kernel missed:** "a wiki page whose footnote cites
   ..." (knowledge, publishing) and "it exits 2 and says ..." (init,
   compliance, regeneration).
7. **Run-time step registration remains** in files outside this cycle's
   scopes: `knowledge_wiki.clj`, `verification_status.clj`, and the calls in
   `spec_parser.clj` and `spec_stamper.clj`. Also, `measurement_cycle.clj`,
   `architecture_views.clj` and `kernel_runner.clj` still define
   kernel-owned texts. The kernel narrows them so its own win; they should be
   deleted when their scopes are next rebuilt.
8. **Which environment a scenario's commands inherit:** the README says only
   what the steps set, but git and the site builder need PATH. The kernel
   passes the process's environment minus RESULTS_DIR. Say so.
9. **A document's own findings under release and audit** refuse with exit 2;
   the glossary gives no exit code. Say which.
10. **ClausesIndexed gained `:declared`** (the declared list a clause was
    reached through), so the clauses page can group by declared list. Add it
    to events.edn.
11. **New rules a builder named:** `clause-line-malformed`,
    `sources-history-unchecked`, `tag-misplaced`, `tag-unknown`,
    `id-not-issued`. Give each a scenario or drop it.
12. **spec-0100 has no Given that snapshots the spec;** its step compares the
    fixture with the room's spec.
13. **Three scenarios read two ways** (both readings pass):
    - spec-0121, "no longer lists it"
    - spec-0199, "example step"
    - spec-0126, no `--sources`, or `--sources` without loyly

    Pick one each.
14. **Minor:**
    - `trace --results DIR` prints absolute paths.
    - A component's prose H1 is published under "## Design".
    - The existing-OUT refusal said `OUT OUT` (fixed in this build).

## Process

- **Fixtures:** about 14 unit-test files outside this cycle's scopes still
  leave directories in /tmp. The integrator cleared about 1,850.
- **Breaches:**
  - The compliance builder wrote one file beside its room and removed it.
  - Grader C read one file outside its room (a cycle 03 checklist) and did
    not use it.
