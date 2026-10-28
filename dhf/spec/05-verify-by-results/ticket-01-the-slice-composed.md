# Ticket 01: The slice composed, and a run's results read

Slice: `05-verify-by-results` (`plan.md`, Scope In: "Results: Allure results
as the oracle, whoever wrote them"). Build: the component build of ADR 0006
(`dhf/decisions/adr-0006-wasm-components.md`, cycle 6 of its build order),
its stack in the first section of `dhf/harness/stack.md`. Architecture:
`dhf/wit/` (`kernel-runner.wit`, `verification-results.wit`,
`verification-status.wit`, `verification-trace.wit`,
`verification-release.wit`, `slice-05.wit`, `slice-05.wac`; `tasks.wit`,
`spec-parser.wit`, `kernel-plugins.wit` and `spec-check.wit` as slices 01, 02
and 08 left them). Builds on slices 01, 02, 04, 03 and 08 as built.

## What it delivers

The whole path through the slice before any status is derived:

- `wasm/components/kernel-runner/`, `verification-results/`,
  `verification-status/`, `verification-trace/`, `verification-release/` and
  the tasks component (`verify-by-results-tasks`, ED-08), each building to
  its own component that targets its world (`kernel-runner-component`,
  `verification-results-component`, `verification-status-component`,
  `verification-trace-component`, `verification-release-component`,
  `verify-by-results-tasks`), each world and `verify-by-results` given the
  host's standard interfaces as ED-11 settles. In this ticket status prints
  its result with every scenario unverified, and run, trace and release
  answer `cannot-run: TASK is not built yet` (exit 2); tickets 02 to 07 fill
  them.
- The composition: `wac compose dhf/wit/slice-05.wac` into
  `wasm/build/deps/rdm/verify-by-results.wasm`, and the command with the
  slice added. `rdm-spec.wac`, as cycles 2 to 5 leave it, gains these lines
  and rewrites none (ADR 0006, "How the command reaches the slices"), and its
  comment's "Slices built" gains 05: the slice's instance, below slice 02's
  and slice 08's (an instance is declared before it is used) and above the
  command's, and the tasks wiring line inside the command's arguments, above
  the spread (both as text in "Lines for rdm-spec.wac", below).
  The stand-in's spread stays: it gives release the clauses stage until
  slice 10 (ED-07), and answers for slices 06, 07, 09 and 10. `rdm-spec.wit`
  changes only in its comment: "Slices built" gains `05, verify-by-results
  (slice-05.wit)`; `events.wit`'s header comment names
  `verification-events` (ED-05). The planner composed exactly this against
  stand-in components built from the declarations: the slice alone, and the
  command with slices 01, 02, 03, 04, 08 and 05 (the not-built stand-in with
  `include check-stages-not-built;`).
- verification/results' `read`, whole: every `<uuid>-result.json` of the
  directory as given, each named DIR/name; a file that is not JSON, or whose
  status is not Allure's, unreadable with why in one line (spec-0062,
  spec-0313; G-14 for "unknown"); no Allure result there, or no such
  directory, why it cannot run (spec-0061, spec-0107). Every other file
  (attachments, containers) is not a result. The file's text kept as read
  (ED-10).
- verification/status' `run`, the path: the results read, each unreadable
  file one line on the error stream, the exit as G-13 settles; a reading
  that cannot run, `cannot-run` (exit 2).
- The tasks component's entries, the rows of run, status, trace and release
  exactly as spec-0176 writes them, and `run` handing each to its component
  with the results directory the command settled.
- `wasm/test.sh` extended to the feature files named for this build's new
  components (`runner`, `results`, `status`, `trace`, `release`, in every
  slice).

## Scenarios it satisfies

- `@spec-0061` An empty results directory is refused, `results.feature`
- `@spec-0062` A results file that cannot be read is named, never skipped, `results.feature`
- `@spec-0313` A result whose status is not one of Allure's is unreadable, `results.feature`

3 scenarios, verified in cycle 6. This ticket also unblocks slice 01's
spec-0179, spec-0193 and spec-0219 (status run with its results directory),
pending since cycle 1 (`01-start-a-project/pending.md`).

## Components and interfaces it touches

verification/results (`verification-results`), verification/status
(`verification-status.run` as a shell), kernel/runner, verification/trace and
verification/release as shells, the tasks component, the composition
(`slice-05.wac`, `rdm-spec.wac`).

## Done when

- `wasm-tools component wit dhf/wit` parses; each crate's component targets
  its world; `wac compose` writes the slice, and the command with the lines
  above.
- `sh wasm/rdm-spec status --results DIR` on a directory of passing results
  exits 0; `sh wasm/rdm-spec help` still lists every task as spec-0176
  writes it.
- verification/results' unit tests cover each status, an unreadable file
  (bad JSON, a status not Allure's, a message holding a newline), a
  directory with no result, and a missing one.
- `wasm/test.sh` writes a passing result for each scenario above, and slices
  01's, 02's, 03's, 04's and 08's scenarios still pass.

## Lines for rdm-spec.wac, as text

The instance, after `let plugins = ...;` and slice 02's instance, before
`let start-a-project = ...;`:

    let verify-by-results = new rdm:verify-by-results {
        "rdm:spec/events@0.1.0": write-the-spec["rdm:spec/events@0.1.0"],
        "rdm:spec/spec-parser@0.1.0": write-the-spec["rdm:spec/spec-parser@0.1.0"],
        "rdm:spec/kernel-plugins@0.1.0": plugins["rdm:spec/kernel-plugins@0.1.0"],
        ...not-built,
        ...
    };

Inside `start-a-project`'s arguments, above `...not-built,`:

    verify-by-results: verify-by-results["rdm:spec/tasks@0.1.0"],
