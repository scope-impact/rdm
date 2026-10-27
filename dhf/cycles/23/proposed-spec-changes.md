# Cycle 23: proposed spec changes

Cycle 23 built spec-27, 28 and 29 (scoped, 13 components, on cycle 22's
code). 162 of 163; the one fail (spec-0364) is the grading room's, as in
cycle 22. The scoreboard: 1 better (spec-0354, the broken-pipe line gone),
0 worse. The held-still oracle, spec-26 to spec-29: 12 cases, 0 differences.

## Contradictions the spec holds

1. **Exit 2 and ratified.edn.** The glossary says a verify that cannot run
   writes no ratified.edn; spec-0361 and spec-0383 expect it written (the
   builder and grader c both found this). Proposal: a check that cannot run
   is recorded `:refused` in ratified.edn, and the glossary says so.
2. **The Check request's keys.** spec-0362 says the request "was" exactly
   four keys; spec-0391 adds `:results`. Proposal: spec-0362 names the keys a
   request holds at least, or lists `:results`.

## Several spec folders (spec-29), what the graders saw unstated

3. **check and stamp's result with several specs** reports only the last
   spec (`:scenarios 1` for 5; stamp listing only `infra-0001`). Proposal: a
   scenario stating the result is per spec, keyed by name.
4. **scopes with several specs** gives `:shared []` for a changed
   `dhf/sdd/auth/glossary.md`, `model.edn` or `needs.edn`; with one spec a
   changed glossary is a shared file. Proposal: each spec's shared files are
   shared within that spec.
5. **A Check plugin's `:results` path** is relative to the project root, but
   the plugin runs in the checkout. Proposal: the request gives an absolute
   path, or says it is the project root's.
6. **glossary.md and domain.edn** have no observable effect on check;
   "read from its folder" cannot be seen for them. Proposal: drop them from
   spec-0385's list, or name what reads them (the ddd-domain plugin).

## Smaller gaps

7. **Fence with a missing Gate plugin** fails with a raw exception, not a
   FILE: RULE: finding (grader b). Proposal: a gate plugin whose command
   cannot start is `cannot run`, naming the plugin.
8. **spec-0058:** changing the id prefix leaves `@spec-` ids unrecognised,
   so stamp adds a second id. Proposal: say what an old-prefix id is.
9. **spec-0068 vs spec-0313:** `unknown` is a status row in one, absent
   from the valid list in the other.
10. **spec-0173:** a cycle's context scope does not narrow what verify
    requires; spec-0389 narrows only by spec (builder decision 6). State it.
11. **spec-0022:** `[]` versus an empty file for "no verdict".
12. **spec-0267:** release names an unreferenced clause but says
    `:refused 0`.
13. **spec-0324** is a plain Scenario carrying Examples: make it an outline.
14. **The step API** (`defstep`, `:steps-dir` under an implementation path)
    is stated only in stack.md; graders building fixtures needed it.

## The way of working

15. **The installed release scores one verdicts file;** the three graders'
    files were merged by hand. Cycle 23's build takes several (spec-0379).
16. **Test-first unshown** for kernel/plugins and spec/parser: no failing
    run was kept. Not a spec change; the brief already asks for it.
