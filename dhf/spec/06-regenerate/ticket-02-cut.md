# Ticket 02: Cut, once, only on a passing check

Slice: `06-regenerate` (`plan.md`, Scope In: "Cut: a pushed tag on the
default branch, read and nothing else"); `cut.feature`; the glossary's
"Cut". Builds on ticket 01.

## What it delivers

regeneration/cut's `run`, "rdm-spec cut", in the project's root (a folder
of its repository too: the tag is the repository's):

- HEAD not the commit `origin/HEAD` names: exit 2, naming the branch
  `origin/HEAD` points to (spec-0163); no `origin` or no `origin/HEAD` as
  G-04 settles.
- HEAD already carrying a `spec-N` tag: no tag added, saying the commit is
  already spec-N, nothing else run (spec-0162; G-01 for its exit and
  result, G-05 for the order).
- "rdm-spec check" run as a command, `RDM_SPEC check`, through the host in
  the project's root (ED-01; G-03): on its exit 1, cut exits 1, tags
  nothing, and writes each of check's finding lines unchanged (spec-0224);
  RDM_SPEC absent, cut cannot run, saying so.
- Else HEAD tagged `spec-N`, N one more than the highest cut of the
  repository (G-02), and the tag pushed to `origin` (`git push origin
  spec-N`), its result on standard output as G-01 settles; a push that
  fails as G-04 settles (spec-0036).

## Scenarios it satisfies

- `@spec-0036` HEAD on origin/HEAD is tagged spec-N and the tag is pushed, `cut.feature`
- `@spec-0162` Cutting a commit already cut changes nothing, `cut.feature`
- `@spec-0224` A HEAD whose spec fails the check is not cut, `cut.feature`
- `@spec-0163` A HEAD that is not origin/HEAD is refused, `cut.feature`

4 scenarios, verified in cycle 7. Their fixtures push to a bare repository
the scenario makes as `origin`, with hooks off.

## Components and interfaces it touches

regeneration/cut (`run`, `resolve`).

## Done when

regeneration/cut's unit tests cover the next tag's number, a commit
already cut, HEAD behind or ahead of `origin/HEAD`, check's lines relayed
on its exit 1, and a push that fails; `wasm/test.sh` writes a passing
result for each scenario above; earlier tickets' results unchanged.
