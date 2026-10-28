# Cycle 25: proposed spec changes

Cycle 25 built the whole project from spec-33 (slice development), slice by
slice. Graded 341 of 343: the two fails are the spec's (labels.yaml). Not
scored: the scorer scores only a verified build, and this one was not.

## The judge cannot verify a change to its own contract

1. **Verify by cycle 24's release refused the build on spec-0403 alone**
   (468 findings, every one `mapping-differs: tag`; ratified-by-cycle24.edn).
   spec-32 changed the tag labels' contract; the installed judge reads the
   old one, and the oracle's goldens hold it too, so the build's own verify
   stops at the gate. Proposal: a contract change to verify's mapping comes
   in two steps, the judge first accepting both readings for one cycle; or
   the way of working names the case and lets the spec owner verify with the
   build under judgement on the graders' evidence.

## Corrections

2. **spec-0416's comment cites an incident that was not one.** Slices 11 and
   12 listed no component because spec-0398 lists a component two touched
   slices share in the first only; both builds were right. The scenario is
   sound; its comment is to say so.
3. **spec-0405's affordances row** asks check to run a :Check answerer, which
   only verify asks. And the affordances template is defective: its probe.sh
   needs a URL and reads dhf/harness/conformance.cjs, where `plugin add` does
   not put it. Proposal: the row says verify; the template is mended.

## Unstated or defective

4. **spec-0192:** whether a bootstrap cycle has a score.edn.
5. **A missing gate plugin** crashes fence ("File does not exist", exit 1,
   stdout {}) instead of cannot-run (exit 2): raised in cycle 23 too.
6. **A Gate plugin that does not read its input** gets "ERROR while copying
   :in option: Stream closed" on the error stream (spec-0232), as cycle 22's
   subscribers did.
7. **A failed oracle pull** leaves an empty /tmp/oracle-pull-* directory.
8. **Several specs:** result links use the configuration's :needs, not each
   spec's own needs file.
9. **spec-0121:** a source removed from sources.edn is not reported as a
   dropped pin while absent.
10. **spec-0391:** what :results is relative to (the builder chose the project
    root, else the checkout). **spec-0370:** "its .gitignore" is the room's.
11. **A malformed cycle.edn** (a stray brace) is read as its first form.
12. **spec-0218:** a broken result is reported as failed.
13. **The regulated profile** has no configuration example in any scenario.

## The way of working

14. Unit tests: 130 (cycle 24: 14). Slice 01's were written after its code.
15. A grader read three lines of its own bin/rdm-spec wrapper, another made a
    no-op cd /tmp; nothing written outside any room.
