# Ticket 06: Verify trusts only the cut

Slice: `06-regenerate` (`plan.md`, Scope In: "Verify: the cut trusted
alone"); `verify.feature`, Rules "Verify trusts only the cut", "Verify
records what it verified" and "A project declares its checks";
`11-project-in-a-folder/verify.feature`; the glossary's "Verify", "Check"
and "Cycle". Builds on ticket 01.

## What it delivers

verification/verify's `run`, "rdm-spec verify ROOM CYCLE", up to its first
check, in ED-10's order:

- `dhf/cycles/CYCLE/cycle.edn` read first: missing, exit 2 naming it, no
  ratified.edn (spec-0202, spec-0203); its `:cut`, and its `:scope` or
  `:slice` (G-23).
- The plugins' declaration at the project's root refused first, exit 2
  (spec-0441, its row `verify ROOM 03`; G-21); then kernel-plugins' `gate`
  with `{:request :Gate :task :verify :cut CUT :cycle CYCLE}`: a failing
  answer relayed unchanged, no ratified.edn (spec-0358, spec-0232, its row
  `verify ROOM 03`).
- The checkout, `dhf/cycles/CYCLE/build`, made afresh (G-22): the cut's
  committed files of the project's folder, at its root, extracted with
  cut's `extract`, no history (spec-0202); every path of the cut's
  `:implementation` removed and filled with exactly what the room holds
  there, as git would commit it, links as links (spec-0204, spec-0370;
  ED-03; G-27). The configuration and spec used are the cut's, never the
  room's (spec-0205).
- The cut's configuration held before any check runs: no `:verify`, no
  `:tests` check, a kind not of the four, a `:serves` probe in the
  implementation, a `:tests` check with no command, each exit 2 saying why,
  no check run, no ratified.edn (spec-0316).
- The spec at the cut read with spec/parser (ED-09): a scenario with no id
  refuses the build, exit 1, naming its file and line (spec-0217).
- ratified.edn written on every exit 1 and once a check has run: `:cut`,
  `:room-tree` (G-30), `:outcome`, `:checkout` where it was made, `:checks`,
  and, refused, the reason (G-28).

## Scenarios it satisfies

- `@spec-0202` Verify reads the cycle before anything else it does, `verify.feature`
- `@spec-0203` A cycle with no cycle.edn is refused, `verify.feature`
- `@spec-0204` The checkout's implementation is replaced by the room's, never merged with it, `verify.feature`
- `@spec-0205` Edits the builder made outside the implementation are not used, `verify.feature`
- `@spec-0217` A cut holding an unstamped scenario is not verified, `verify.feature`
- `@spec-0316` A configuration verify cannot trust is refused before anything runs (5 rows), `verify.feature`
- `@spec-0370` The room's implementation reaches the checkout as git would commit it, `11-project-in-a-folder/verify.feature`

7 scenarios, verified in cycle 7. The record of a refusal is written here;
spec-0216, whose refusal is the tests check's, is ticket 07's. This ticket
also unblocks slice 08's spec-0358, spec-0232 (row verify) and
spec-0441 (row verify), and slice 01's spec-0368 (row verify)
(`pending.md`).

## Components and interfaces it touches

verification/verify (`run`), regeneration/cut (`resolve`, `show`, `tree`,
`extract`), spec/parser (`parse`, as slice 02 built it), kernel/plugins
(`declared`, `gate`).

## Done when

verification/verify's unit tests cover each refusal of spec-0316, the
checkout's implementation replaced (a file the room lacks, an ignored file,
a link), and the room's tree hash; `wasm/test.sh` writes a passing result
for each scenario above and for the scenarios it unblocks; no checkout of a
fixture is left behind; earlier tickets' results unchanged.
