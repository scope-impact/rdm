# Ticket 03: The clean room of a cut, behind the Gate

Slice: `06-regenerate` (`plan.md`, Scope In: "Fence: a clean room, the cut
minus what is denied, for the project"); `fence.feature`, Rules "A clean
room is built only from a cut" and "A clean room is the cut minus what is
denied"; `11-project-in-a-folder/fence.feature`; the glossary's "Fence";
`08-plugins/plugins.feature`, Rule "An answerer is asked at its request's
place". Builds on ticket 01.

## What it delivers

regeneration/fence's `run` for the project scope (no `--context`,
`--component` or `--slice`), in ED-10's order:

- The plugins' declaration at the project's root refused first, exit 2, one
  cannot-run line naming the plugin, nothing on standard output, no plugin
  run (spec-0441, its row `fence ROOM spec-3`); then kernel-plugins' `gate`
  with `{:request :Gate :task :fence :cut CUT}` (`:cut` as given): a
  failing answer relayed unchanged, exit as it answers, no room built
  (spec-0357, spec-0448, spec-0449, spec-0232, its row `fence ROOM
  spec-3`).
- CUT resolved with cut's `resolve`: a revision carrying no `spec-N` tag
  refused, exit 2, saying regeneration reads only a cut (spec-0037).
- The cut's tree of the project's folder (cut's `tree`), each path denied
  or kept by the cut's configuration: the project scope denies every
  `:implementation` path; the measurements and the generated output as
  `:deny` names them; a path neither denied nor kept as G-09 settles.
- A kept file carrying the bytes of a removed implementation file stops
  the fence: exit 1, naming both, no room (spec-0295; G-11).
- ROOM (G-12) made a repository whose working tree is the kept entries,
  extracted with cut's `extract` at ROOM's root, unchanged, and nothing else
  (no untracked, no ignored file: spec-0294, spec-0369), committed as one
  root commit with hooks off (ED-03; G-14), so no object of a denied path is
  in it (spec-0038, spec-0182). Its result on standard output as G-08
  settles.

## Scenarios it satisfies

- `@spec-0037` A commit that is not a cut is refused, `fence.feature`
- `@spec-0038` The clean room is the cut's committed files minus the denied ones, as one root commit, `fence.feature`
- `@spec-0294` Uncommitted and ignored files never reach a clean room, `fence.feature`
- `@spec-0295` A kept file carrying a removed implementation file's bytes stops the fence, `fence.feature`
- `@spec-0182` The project scope keeps no implementation, `fence.feature`
- `@spec-0369` A cut is read as committed, however the project's working tree differs, `11-project-in-a-folder/fence.feature`

6 scenarios, verified in cycle 7. This ticket also unblocks, without their
slices being rebuilt: slice 08's spec-0357, spec-0448, spec-0449, spec-0232
(row fence) and spec-0441 (row fence), and slice 01's spec-0368 (row fence)
(`pending.md`).

## Components and interfaces it touches

regeneration/fence (`run`), regeneration/cut (`resolve`, `tree`,
`extract`), kernel/plugins (`declared`, `gate`, as slice 08 built them).

## Done when

regeneration/fence's unit tests cover the denied and kept paths of a
configuration like rdm-spec's own, the bytes rule, and a ROOM refused;
`wasm/test.sh` writes a passing result for each scenario above and for the
scenarios it unblocks; no fixture repository and no ROOM is left behind;
earlier tickets' results unchanged.
