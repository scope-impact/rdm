# Ticket 07: A run observed from outside, begin to end

Slice: `07-measure` (`plan.md`, Scope In: "Observe: each run's time, cost
and what it touched outside its room"); `observe.feature`, Rule "A run is
observed from outside, begin to end"; the glossary's "Run", "Observation"
and "Breach". Builds on ticket 01.

## What it delivers

agency/observe's `run` for "observe begin CYCLE ROLE ROOM" and "observe end
CYCLE ROLE [--tokens N]":

- The configuration's `:agency {:watch [DIR ...] :tmp DIR}` read at the
  project's root, `~` the home directory from the environment (G-36).
- begin: the time; the entries of each watched directory and of the
  temporary directory, read through the root folder the host opens (ED-02;
  G-29); the project's git status outside `dhf/cycles/CYCLE/` (ED-10); kept
  in the cycle's directory until end (ED-08). A run of the role already
  begun and not ended, exit 2 (G-35).
- end: no run of the role begun in the cycle, exit 2 saying no run of the
  role ROLE began in cycle CYCLE (spec-0292). Else the same readings again,
  compared: `:writes-outside` every entry added or changed in a watched
  directory, never ROOM, the project or what holds them (spec-0287; G-29);
  `:tmp-left` the entries of the temporary directory not there at begin
  (spec-0288; G-30); `:repo-touched` every path of the project's folder
  whose status changed, outside the cycle (spec-0289; G-33);
  `:results-in-room` whether the configured results directory was written
  inside ROOM (spec-0290; G-34); `:unobserved` naming reads outside the
  room, and each watched directory that could not be read (spec-0291;
  G-32). One entry appended to `dhf/cycles/CYCLE/runs.edn` with exactly
  `:role`, `:room`, `:began`, `:ended`, `:minutes`, `:tokens` and
  `:defence`, the defence exactly its five keys (spec-0286; G-31); one
  warning line per write outside the room, naming it; exit 0, whatever the
  defence holds (spec-0287): observing never stops a run.

## Scenarios it satisfies

- `@spec-0286` A run's record has one shape, `observe.feature`
- `@spec-0287` A write outside the room is recorded, and the run is not stopped, `observe.feature`
- `@spec-0288` Entries left in the temporary directory are counted, `observe.feature`
- `@spec-0289` A change to the project outside its cycle is recorded, `observe.feature`
- `@spec-0290` Results written into the room are recorded, `observe.feature`
- `@spec-0291` What cannot be observed is said, never assumed clean, `observe.feature`
- `@spec-0292` Ending a run that never began cannot run, `observe.feature`

7 scenarios, verified in cycle 8.

## Components and interfaces it touches

agency/observe (`run`), the host (`rdm:host/process`, for git; its root
folder, for the watched directories).

## Done when

agency/observe's unit tests cover a write in a watched directory, a write
in ROOM inside a watched directory (not counted), an entry left in the
temporary directory and one removed, an untracked file in the project and
one in the cycle's directory (not counted), results inside ROOM, a watched
directory that cannot be read, and end without begin; `wasm/test.sh`
writes a passing result for each scenario above, its fixtures' watched and
temporary directories made inside the run's own temporary directory, never
`/` or the home directory; earlier tickets' results unchanged.
