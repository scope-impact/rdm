# The gate and the instrument

## "Done" is ratified

A "done" claim is ratified by a script the claimant does not control, inside
a boot budget that is named and whose margin is reported.[^pieces] The gate
proves the suite green and says nothing about whether the suite exercises
the product; what the suite cannot say, a probe or a person does.[^pieces]

Gates learned their shape from silent lies: a gate once reported success
without exercising the app because a missing config turned the test command
into a no-op that exits 0, so gates assert their tools exist before trusting
them; a runner named its suites twice so a forgotten suite was silently
green, so runners discover suites from the directory and refuse an empty
discovery; an orphaned process once answered a health probe long after its
run died, so the gate refuses to start when the port is held.[^failures] This
harness's `verify` carries each: a suite that wrote no result is a refusal,
the suite runs twice, and a run past the budget is refused.

## The gate reads results, not runners

rdm-spec decided that results are the oracle for status: a scenario is
verified when a test run in any language writes a result in Allure's model
whose label is the scenario's id with status `passed`, and no result for it
that did not pass; the harness reads only results, never a test runner, so a
product may test in any stack.[^allure] This harness keeps that rule (INV-11)
and widens what a result carries: the tool's attachments (a browser trace, a
screenshot, a transcript) travel with it into the cycle, so the evidence a
grader and the owner read is what the suite saw, in one reader, whatever
tool wrote it.

## The result model, as Allure states it

A test result file stores data related to a single run of a single test, as
a JSON object named `{uuid}-result.json`.[^rf] Its labels are an array of
name-value objects, and a result can have multiple labels with the same
name; integrations set `host`, `thread`, `language` and `framework`, and the
author may set labels with arbitrary names.[^rf] Its attachments are stored
as separate files named `{uuid}-attachment.{ext}` and listed on the result
by `name`, `source` and media `type`.[^rf] Its status is one of failed,
broken, passed, skipped, unknown;[^rf] its steps carry the same fields and
nest.[^rf] Two runs of the same test with the same parameters share a
`historyId`.[^ids] A container file groups fixtures around results.[^cf]

The harness takes from this exactly what INV-11 needs: the file per run,
the `status`, two labels of its own (`item`, `tool`) among whatever the tool
sets, and the attachments beside the file. It does not read steps,
`historyId`, containers or the report-side fields; the item id is the
harness's identity, not Allure's.

## The checklist is derived, never written

One item per stamped scenario; ids are handed out once and never computed
from wording, so rewording a rule cannot break the cycle-to-cycle
comparison.[^pieces] A hand-kept list goes stale silently, so the checklist
is derived from the spec and the spec is its only author.[^pieces] An
unstamped scenario stops the checklist rather than shrinking it, and an empty
spec stops it rather than producing an empty one.[^spec]

## Honest denominators

Zero verdicts means the grading did not happen, not that everything failed;
an unreached item is recorded as a fail, so a gap can never quietly shrink
the denominator.[^pieces] A scoring run whose every batch failed on an outage
once committed "scored" over an empty record; the instrument now dies loudly,
because an unrecorded measurement is recoverable and a false record is
not.[^failures]

The scoreboard shows both directions on the shared items, never the net; a
moved denominator is restated out loud, and new items are counted separately
because they are the one holdout nobody has tuned.[^pieces] A seeded
holdback (a quarter of the items scored and never listed) was built and
retired: it measures the operator's repairs only when a repair loop runs
blind to the hidden items, and none did; new scenarios are the holdout that
needs no seed.[^failures]

rdm-spec's instrument also learned vacuous passes: a scenario graded pass
without any check that the command ran, and graders' own goldens measuring
consistency rather than truth.[^retro] Its remedies, every output something
reads stated exactly, and a golden holding nothing the spec does not
state,[^retro-next] are rules of this harness's spec.

## The goldens, and the three reasons one can change

The harness's own oracle judges its tools against recorded goldens, byte for
byte, refusal messages included; a golden that moves is an alarm, so each
move is written down with its reason.[^goldens] Three events look alike in a
diff and are never confused: the instrument's behaviour changed (serious;
needs a reason, and a golden may not be re-recorded to get a green); the
wording was replaced (re-recording is correct, and the entry is its record);
the spec grew (the test is that the diff is additions only: no existing item
moves).[^goldens] rdm-spec added a fourth case the self-hosting created,
where a spec change altered the judge's own contract and the installed judge
refused every build of the new contract;[^judge] holding the harness still
(INV-3) removes that case.

[^pieces]: `raw/loyly/harness/README.md@17d68d2`, "The pieces, in pipeline order".
[^failures]: `raw/loyly/harness/README.md@17d68d2`, "The failure modes this shape was learned from".
[^spec]: `raw/loyly/harness/spec.feature@17d68d2`, rules "An unstamped scenario stops the checklist rather than shrinking it" and "An empty spec stops the checklist rather than producing an empty one".
[^retro]: `raw/rdm/retrospective-01-12.md@a4641ae`, section 1, GRADE + SCORE.
[^retro-next]: `raw/rdm/retrospective-01-12.md@a4641ae`, "What follows for the next spec change", items 1 and 2.
[^goldens]: `raw/loyly/harness/README.md@17d68d2`, "The goldens, and the three reasons one can change".
[^judge]: `raw/rdm/docs/wow/way-of-working.md@a4641ae`, "When the spec changes the judge's own contract".
[^allure]: `raw/rdm/decisions/adr-0002-spec-first-regeneration.md@a4641ae`, "Decision", "Allure results are the oracle for status".
[^rf]: `raw/allure/docs/how-it-works-test-result-file.md` (fetched 2026-10-29): the opening paragraphs, "labels", "attachments", "status", "steps".
[^ids]: `raw/allure/docs/how-it-works-test-result-file.md` (fetched 2026-10-29), "historyId".
[^cf]: `raw/allure/docs/how-it-works-container-file.md` (fetched 2026-10-29).
