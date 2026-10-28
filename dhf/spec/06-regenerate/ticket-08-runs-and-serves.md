# Ticket 08: The :runs and :serves checks, with their margins, and the record of a pass

Slice: `06-regenerate` (`plan.md`, Scope In: "Verify: ... the project's
checks (tests, serves, runs, plugin) run the same way and recorded");
`verify.feature`, Rules "A project declares its checks, and verify runs
every one the same way", "A :serves check proves the app comes up and is
this application", "A :runs check proves a command answers as the project
expects" and "Verify records what it verified"; the glossary's "Check".
Builds on ticket 07.

## What it delivers

verification/verify's `:runs` and `:serves` checks (ED-06), and its record
and result once every check passed:

- A command the host cannot start, in any check: exit 2, naming the check
  and the command (spec-0317).
- `:runs`: its command run with its budget as its time limit; an exit other
  than `:expect`'s, a first line not starting with `:stdout-starts`, or the
  budget run out, `:failed`, naming the difference (spec-0322); its entry
  `{:name :kind :runs :outcome :seconds :budget B :margin M}`, M to 0.01.
- `:serves`: its port tried first, a port another process holds refusing
  the gate, exit 2, naming it, no app started (spec-0320), one held only by
  closing connections free (spec-0346); its app spawned in the checkout
  with `{port}` filled in, polled K times, poll i at i x S/K seconds, an
  answer any HTTP response to `GET /`, an accepted connection with none no
  answer (spec-0341); no answer within its budget `:failed`, naming the
  budget (spec-0319); else its probe run in the checkout with `{url}` as
  `http://127.0.0.1:PORT/` and its `:probe-seconds` (30 by default) as its
  limit (spec-0366): exit 1 `:failed`, relaying its output (spec-0321); past
  its limit stopped, `:failed`, named as timed out after its seconds,
  relaying what it printed (spec-0367); its entry with `:answered-on-poll`,
  `:polls` and `:margin`, the polls left (spec-0318; G-31). The app stopped
  with its process group on every way out of the check, so no process it
  started is left (spec-0318, spec-0319, spec-0367).
- On a pass: exit 0, `{:verify {:cycle :cut :outcome :passed :checks
  [...]}}` exactly (spec-0314), and ratified.edn holding exactly `:cut`,
  `:room-tree` in full (G-30), `:outcome :passed`, `:checkout
  "dhf/cycles/CYCLE/build"` and `:checks` as standard output reports them
  (spec-0215).

## Scenarios it satisfies

- `@spec-0317` A check whose command is not found refuses the gate, `verify.feature`
- `@spec-0322` A command that answers otherwise than expected fails the check (3 rows), `verify.feature`
- `@spec-0314` Verify reports every declared check, in order, with its outcome and margin, `verify.feature`
- `@spec-0215` Verify writes ratified.edn with the cut, the room's tree, the outcome and every check, `verify.feature`
- `@spec-0318` A served app that answers and passes its probe passes, with its margin, `verify.feature`
- `@spec-0366` A probe is given the app's url, ending in "/", and has its own time limit, `verify.feature`
- `@spec-0367` A probe that runs past its time limit is stopped and named as having timed out, `verify.feature`
- `@spec-0319` An app that does not answer within its budget fails, naming the budget, `verify.feature`
- `@spec-0320` A port already held refuses the gate rather than measure another process, `verify.feature`
- `@spec-0346` A port held only by the closing connections of an app that has gone is free, `verify.feature`
- `@spec-0321` An app that answers but fails its probe is not this application, `verify.feature`
- `@spec-0341` An app that accepts connections but never sends an HTTP response does not answer, `verify.feature`

12 scenarios, verified in cycle 7.

## Components and interfaces it touches

verification/verify (the `:runs` and `:serves` checks, the record), the
host's `run`, `spawn` and `stop` (slice 01's ED-15), the standard
interfaces' sockets and clocks.

## Done when

verification/verify's unit tests cover the polls' times and margin, a port
held and one in closing connections, an answer and an accepted connection
with none, a probe that times out, and a `:runs` margin to 0.01;
`wasm/test.sh` writes a passing result for each scenario above, and no
process a scenario started is running after it; earlier tickets' results
unchanged.
