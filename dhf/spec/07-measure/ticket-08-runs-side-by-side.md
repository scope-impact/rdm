# Ticket 08: Two cycles' runs side by side, never netted

Slice: `07-measure` (`plan.md`, Scope In: "Observe"); `observe.feature`,
Rule "Two cycles' runs are compared side by side, never netted"; the
glossary's "Breach". Builds on ticket 07.

## What it delivers

agency/observe's `run` for "observe report CYCLE CYCLE": each cycle's
runs.edn read; on standard output exactly `{:observe {:cycles [A B] :runs
{A n B n} :minutes {A m B m} :tokens {A t B t} :breaches {A b B b}}}`,
`:minutes` and `:tokens` the sums of each cycle's runs, `:breaches` as
G-38 settles (spec-0293); a cycle with no runs.edn as G-38 settles; never a
net, and never folded into a score. Writes nothing.

## Scenarios it satisfies

- `@spec-0293` The report sets two cycles' runs side by side, `observe.feature`

1 scenario, verified in cycle 8.

## Components and interfaces it touches

agency/observe (`run`).

## Done when

agency/observe's unit tests cover spec-0293's output exactly, a run with
no tokens, and a run breaching in more than one way; `wasm/test.sh` writes
a passing result for the scenario above; earlier tickets' results
unchanged.
