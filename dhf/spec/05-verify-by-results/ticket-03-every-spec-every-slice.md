# Ticket 03: Status of every spec and every slice, and the event it publishes

Slice: `05-verify-by-results` (`plan.md`, Scope In: "Status");
`12-several-specs/status.feature`, `13-slice-development/status.feature`;
`events.edn`, VerificationDerived; the glossary's "Spec folder" and "Slice
development". Builds on ticket 02.

## What it delivers

- Several specs (`:specs`): every stamped scenario of every spec, each with
  its spec's name (G-17), and `--spec NAME` deriving that spec's only; a name
  no spec bears as G-19 settles.
- The slices layout (`:spec-layout :slices`): `:slices`, one entry per slice
  in the order of their numbers, `{:slice :status :verified :scenarios}`, the
  status as its plan's `status:` line writes it, beside every scenario's
  status (G-18); `--slice SLICE` deriving that slice's scenarios only.
- VerificationDerived: `derive` returns the event (`verification-events`,
  ED-05), `:statuses` one entry per scenario derived; `deliver` hands it, as
  EDN, to kernel/plugins' `deliver` after the task's own work, the plugins'
  answer joining the task's report (ED-06); the status task delivers it, and
  trace and release as G-20 settles (tickets 04 and 05 call it).

## Scenarios it satisfies

- `@spec-0408` Status lists every scenario of every spec, each with its spec's name, `12-several-specs/status.feature`
- `@spec-0419` Status reports each slice's plan status and its scenarios verified of all, `13-slice-development/status.feature`

2 scenarios, verified in cycle 6.

## Components and interfaces it touches

verification/status (`derive`, `deliver`, `run`; `verification-events`),
spec/parser (`specs`, `parse`), kernel/plugins (`deliver`, as slice 08 built
it).

## Done when

verification/status' unit tests cover two specs, `--spec`, two slices in
the order of their numbers, `--slice`, and the event's EDN (`{:event
:VerificationDerived}` merged with exactly the keys it carries, held by the
bus' catalogue); a subscriber to `:VerificationDerived` in a fixture runs
once on `sh wasm/rdm-spec status`, given that EDN; `wasm/test.sh` writes a
passing result for each scenario above; earlier tickets' results unchanged.
