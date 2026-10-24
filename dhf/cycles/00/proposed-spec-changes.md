# Cycle 00: proposed spec changes

The bootstrap build: 202 scenario results, 201 passed, 1 failed (spec-0178,
a stale line number in the spec itself), plus 109 unit tests, all passing.
Ten builders (the kernel, then one per bounded context) each listed what the
spec left open or got wrong. This file merges those lists. Nothing here is in
the spec yet.

## A. Wrong or contradictory: fix first

1. **Ratify lets failing tests through.** Its one condition is "the test
   command exits 0 and writes results naming every scenario". A command can
   exit 0 over failed or broken results. Require every non-@wip scenario's
   result to be passed. (ratification)
2. **spec-0178 points at line 12** of `spec/check.feature`, now a Given. Say
   "a scenario with no level tag" and drop the line number. (kernel, spec)
3. **Duplicate id contradiction.** spec-0074 refuses an id used twice, but
   `model.edn` gives the system and the container both `:rdm-spec`. Rename
   one, or say the system may share its container's id. (architecture)
4. **spec-0093 says Lint has fifteen scenarios;** `lint.feature` holds 13. (publishing)
5. **Two event catalogues.** The bus checks against a built-in copy of rdm-spec's
   events; the lint checks a project's `:events`. Say which one. (kernel, architecture)
6. **View freshness hash is undefined,** yet two contexts must agree on it.
   Name the hash and its canonical input, or have check publish freshness as
   an event. (architecture, publishing)
7. **`/tmp/room` is a fixed path** in fence, ratify and score scenarios, so
   concurrent runs collide (worked around with a lock). Say "a new directory ROOM".
8. **Results go to the wrong place.** Ratify passes `RESULTS_DIR` to the test
   command, but `rdm-spec run` takes only `--results` or the configuration.
   Pick one.

## B. Contracts the spec should state, not each builder invent

9. **Event payloads**: SpecParsed, KnowledgeIndexed, VerificationDerived shapes
   live only in docstrings.
10. **Allure result format**: required fields, the labels (`spec`, `component`,
    `context`, `planned`), outline row naming, failed vs broken.
11. **Task output**: each task's EDN result shape; what else may go to stderr
    (warnings, `--events` lines, cannot-run messages) and its format.
12. **Exit codes per refusal**: many refusals don't say 1 or 2. "An id that
    names nothing" exits 2, which blurs "cannot run".
13. **Rule names** in findings are all invented: list them, or say they are free.
14. **Model shape**: required keys and values; which component owns which task,
    and how cross-context tasks run (check needs KnowledgeIndexed before SpecGated).
15. **Tag placement and id format**: where ids, levels, @wip and need tags may
    sit; `@spec-NNNN` digits; stamper with ids above the ledger.
16. **Configuration**: init writes too few keys (`:results-dir`, `:steps-dir`,
    `:spec-dir`, `:raw-dir`, `:needs`, `:wiki-dir`, `:code-layout`,
    `:test-command`); missing key = exit 2 or a default.

## C. Scenarios that can't be observed as written

17. **The same Given means different things** in different contexts ("a stamped
    scenario tagged @wip", "it passes", "the configured stack words include",
    "no file in the spec changes"). Word each per context, or give each one
    meaning and one home.
18. **"It passes"** scenarios (0050, 0051, 0076, 0187) depend on every check
    stage and on views being drawn, and views aren't on the clean room's allow-list.
19. **Kernel not verifiable alone**: 0177, 0178, 0166, 0167, 0193 observe the
    kernel only through `check`, i.e. through other contexts.
20. **Checklist must parse at the cut**, but the parser reads the working tree.
    Say the parser parses at a revision.
21. **`--sources DIR`**: When steps say `rdm-spec check` while a Given supplies
    a checkout. Write `rdm-spec check --sources DIR`. (0128, 0129, 0138)
22. **0135** needs a checkout at f21b508, so it can't be set up without the network.
23. **0047** "traces to UN-003" has no observable output.

## D. Smaller choices the builders made: accept, or spec otherwise

- cut: tag `spec-N`, N from 1, lightweight, no fetch; already-cut exits 0.
- fence: refuses a non-empty ROOM; uses the working tree's configuration.
- ratify: clones the cut into `<cycle>/build`; config from the cut; old results cleared.
- score: an ungraded item is filed as fail with a warning; `{}` is malformed (exit 1), no verdicts exits 2.
- scoreboard: EDN with shared, new and dropped items; a bootstrap on either side compares nothing.
- status/release: planned beats passed; status exits 0 even with failures.
- knowledge: citations are `id:path@hex` (7 to 40 hex digits); stack words matched case-insensitively at word boundaries.
- site: GitHub blob URLs for footnotes; no timestamps; missing builder writes Markdown, then exits 2.
- Installing rdm-spec (and bb) for hooks and CI is never specified.
