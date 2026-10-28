# Invariants

Rules that must hold, and **what enforces each one**: "a principle without a
named enforcement point is a wish: the sentence stays in the document while
quietly ceasing to be true" (loyly:docs/wow/invariants.md@17d68d2). Each
entry gives the rule, what enforces it, and what breaks without it. Where
nothing enforces a rule, the entry says so: those are the wishes, kept by
`docs/wow/` (`harness.md`, `way-of-working.md`) and a person's attention until something
enforces them.

## Enforced by the product

**INV-1: A scenario is one requirement, with one id and one level.**
*Enforced by:* `rdm-spec check` (02-write-the-spec/check.feature). *Without it:* a
checklist item is ambiguous, and a score counts what nobody can name.

**INV-2: The spec never names its stack.** *Enforced by:* `rdm-spec check`
against `:stack-words`. *Without it:* the spec stops being regenerable in
another stack (cycle 20 tests that).

**INV-3: A build is done only when checks the builder does not control
pass.** *Enforced by:* `rdm-spec verify` (06-regenerate/verify.feature):
`:tests`, then `:serves` or `:runs`, the first failure stopping it. *Without
it:* "done" is the builder's claim.

**INV-4: Every stamped scenario is tested before it counts.** *Enforced by:*
verify's tests check: every scenario not tagged `@wip` is named by a passing
result. *Without it:* a missing test passes silently.

**INV-5: A judge never lives in the builder's code.** *Enforced by:* verify
refuses a probe under an implementation path. *Without it:* the builder can
write what judges it.

**INV-6: A clean room holds no implementation and no measurement.**
*Enforced by:* `rdm-spec fence`: the deny-list, every path denied or kept,
the byte guard. *Without it:* a regeneration copies instead of rebuilding.

**INV-7: A golden changes only for a stated reason, and an existing golden
only when named.** *Enforced by:* `oracle.clj record` refuses an unnamed
change. *Without it:* re-recording to get a green destroys the evidence.

**INV-15: The builder never grades its own build, nor reads its own score.**
*Enforced by:* `rdm-spec score` (07-measure/scorer.feature, spec-0142 to
spec-0144): a cycle whose builder is also its grader or spec owner, or that
leaves a role unnamed, is refused, and nothing is written. *Without it:* the
one who built the thing judges it, and the score measures the builder's
confidence rather than the build.

**INV-17: No slice, no code.** *Enforced by:* `rdm-spec check`
(13-slice-development): a feature file outside every slice, or a slice with
no plan, no status or no `decisions.md`, is refused. *Without it:* behaviour
is specified after it is built, and the spec describes rather than leads.

## Enforced by how the harness is kept

**INV-8: The oracle is held still.** *Enforced by:* it lives in
`dhf/harness/oracle/`, a kept path outside `:implementation`, so no fence
removes it and no cycle scopes it; changes go through a reviewed commit.
*Without it:* the judge is rebuilt from the spec it judges (cycles 16 to 18).

**INV-9: Every run is observed.** *Enforced by:* `rdm-spec observe` (writes
outside the room, `/tmp` entries left, the repository touched). *Without
it:* a run's cost and side effects are unknown. *Not enforced:* reads; a
write below a watched directory's own entries.

## Not enforced: kept by docs/wow

**INV-10: Graders grade a black box: never the build's code or tests.**
*Enforced by:* construction, partly: the grading room holds no `src/` or
`test/`, and `bin/rdm-spec` runs the build installed outside it
(docs/wow/way-of-working.md, step 5). Reading outside the room is a rule only:
reads are not observed. *Without it:* grading mirrors the code and stops being
independent.

**INV-11: One builder per cycle.** *Enforced by:* nothing; docs/wow/way-of-working.md
and the integrator. *Without it:* the merge seams of cycles 01 to 09.

**INV-12: A builder's or grader's fixtures never touch anything outside
their room: hooks off in fixtures made by init or adopt
(`git config core.hooksPath /dev/null`), GIT_CEILING_DIRECTORIES set,
TMPDIR inside the room, no process left, no fixture left.** *Enforced by:* partly by `observe` (`tmp-left`); the rest by the
builder skill. *Without it:* the leaks of cycles 06, 07, 09 and 14.

**INV-13: A whole-project cycle at least every fifth cycle.** *Enforced by:*
nothing; docs/wow/way-of-working.md. *Without it:* scoped cycles hide what they do not
rebuild (cycles 12 to 19 ran without one).

**INV-14: The model a cycle used is recorded.** *Enforced by:* nothing;
`cycle.edn` by hand. *Without it:* a change of model reads as a change of
spec.

**INV-16: A test before the code it tests.** *Enforced by:* nothing yet: the
builder keeps `redgreen.log` and reports a ticket whose first run was green;
the reviewer reads both. A log the builder writes is the builder's claim, so
this stays a wish until `observe` records the room's test runs itself.
*Without it:* tests mirror the code (cycles 23 and 25: unit tests written
after their code; cycle 24: 14 unit tests in all).

**INV-18: Planning and building are separate invocations.** *Enforced by:*
nothing; the way of working (step 1, plan phase) and the integrator. *Without
it:* the plan is retro-fitted to whatever was built.

**INV-19: A golden re-recorded because the spec grew changes no existing
item.** *Enforced by:* nothing; the integrator reads the diff (the way of
working, "Re-recording a golden"). *Without it:* a behaviour change is
recorded wearing a growth's clothes.

**INV-20: A plugin copied from a template is known to have drifted, or not.**
*Enforced by:* nothing; `plugin add` records the template's version in
`origin.edn`, and nothing compares the copy with the template since (Löyly's
`modules.sh` does this for its shared files). *Without it:* two projects'
copies of one judge drift silently.

