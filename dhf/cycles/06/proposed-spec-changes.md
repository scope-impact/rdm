# Cycle 06: score and proposed spec changes

The first cycle whose scopes came from `rdm-spec scopes spec-8 spec-9` and
whose fence and ratify ran behind the oracle gate, with recorded goldens.

1. **Scopes:** `rdm-spec scopes spec-8 spec-9`. Seven components, the same
   ones a person would pick by hand.
2. **Fence:** the oracle gate passed on the recorded goldens, then
   `rdm-spec fence` built seven rooms.
3. **Build:** seven builders, test-first, each citing the Löyly rule it
   followed where the spec was silent.
4. **Merge:** **two integration fixes by hand** (below). Afterwards: 233 of
   233 scenario results and 181 unit tests, three runs.
5. **Ratify:** the first attempt was refused on spec-0231. It was fixed, then
   `rdm-spec ratify` passed against spec-9.
6. **Checklist:** `rdm-spec checklist 06`, 71 items, the cycle's scopes.
7. **Grading:** two graders in their own grading rooms.
8. **Score:** **71 of 71** pass.
9. **Scoreboard 05 → 06:**
   - 54 items compared: **0 better, 0 worse**
   - 17 items newly in scope, counted apart
   - 20 items out of scope, not compared, said out loud

## Integration fixes, and what the spec should say so there are none

1. **No step file defined "it passes" at load.** spec/check registered the
   shared steps only inside one Given's body. spec-0090 and spec-0187, which
   never reach that Given, found no definition. Each builder deferred to
   another for it. Say which context owns each shared step text, and that
   the owner defines it when its step file loads.
2. **spec-0231 has no Given,** so its project was implicit. Its step compared
   the run's copy with the room's root. The two agree in a clean room
   (neither holds `dhf/harness/oracle`), but disagree in ratify's checkout of
   the cut. Give spec-0231 a Given, and say that a scenario's project is
   always one the scenario makes, never the repository the tests run in.

## Found by grading (no Then checks it)

3. **Publish with no site builder leaves an earlier site standing**
   (spec-0149), while a broken link removes `site/` (spec-0148). Say that a
   failed publish leaves no site, whatever failed.
4. **The wiki index's own footnote is published without a link;** the pages'
   footnotes are linked. spec-0133 covers pages only. Say whether the index
   is held to the footnote rule.
5. **A source with no pin also gets `source-no-record`** when its record is
   missing. That is extra, not wrong; say whether one source gets one finding
   per rule broken.
6. **Wording the graders had to work around:**
   - spec-0037 names a commit, 1a2b3c4, that no fixture holds.
   - spec-0038's allow-list leaves out `dhf/harness/stack.md`.
   - spec-0042 and spec-0043 use spec-0007 and spec-0008, which are real ids.
   - spec-0049 needs only UN-001 to UN-005 declared.
   - spec-0047 says nothing of a results directory that exists and is empty
     (spec-0061 makes that exit 2).

   Make each Given build what it names.

## Builders' open choices, to settle (cycle 07 list)

7. **The model draws knowledge/sources → spec/check (`KnowledgeIndexed`),**
   but check no longer subscribes. Drop the relationship.
8. **"The results directory as DIR" is defined per context** (trace,
   publish). Make it the kernel's.
9. **The `UN` id prefix collides with need tags** (`init --id-prefix UN`).
   Refuse it.
10. **spec_support moves colliding fixture ids above the ledger.** A fixture
    should raise the ledger instead (third time reported).
11. **`source-no-repository` and `source-no-record` have no scenarios.**
12. **Fences unchecked without a checkout** give no warning. Say whether they
    should.
13. **The skeleton's allow-list names `dhf/harness/stack.md`,** but `init`
    writes no stack.md. Say whether a new project gets one.

## Process

- **The disk filled mid-cycle.** Suites never removed their fixtures, and
  finished rooms were kept. Say that a suite removes what it makes, and that
  the integrator removes finished rooms.
- **Breaches:**
  - Two builders each wrote one file outside their room (removed).
  - The fence builder ran without RESULTS_DIR and left `dhf/allure-results`
    in its room.
  - A grader's unset shell variable wrote `/o` and `/e` at the filesystem
    root. Removing them needs a person.
