# Ticket 02: The declaration: :plugins read, and refused before anything runs

Slice: `08-plugins` (`plan.md`, Scope In: "Plugins: declared in the
configuration ... :env"); `plugins.feature`, Rules "A plugin is declared in
the configuration, in the order it runs" and "What a plugin needs to know is
in the configuration", and `decisions.md`, why each exists. Builds on ticket
01.

## What it delivers

kernel/plugins, `kernel-plugins.declared(root)`, called first by every way
in (the plugins stage, `deliver`, `gate`, `ask`, `run-named`; G-15):

- Each entry read as exactly `:name`, `:command`, one of `:subscribes` (a
  vector of event names) or `:answers` (`:Gate` or `:Check`), an optional
  `:seconds` (60 when absent) and an optional `:env` (spec-0349).
- Refused, exit 2, naming the plugin and saying why, before any plugin runs
  (spec-0350, each row's why as its Examples write it; G-15 for "before
  anything runs" within check): neither or both of `:subscribes` and
  `:answers`; an event rdm-spec does not publish, listing the events it
  publishes (from kernel/bus' `catalogue`); a request other than `:Gate`
  and `:Check`; a name declared twice; a command under the configuration's
  `:implementation` (G-16); an `:env` that is not a map of strings to
  strings (spec-0382).
- A plugin's environment: the caller's, its `:env` added, RDM_SPEC set last
  (spec-0382, ED-07, ED-08).

## Scenarios it satisfies

- `@spec-0349` The plugins are the configuration's :plugins, in this shape, `plugins.feature`
- `@spec-0350` A declaration rdm-spec cannot trust is refused before anything runs (6 rows), `plugins.feature`
- `@spec-0382` A plugin's :env is its environment, beside RDM_SPEC, `plugins.feature`

3 scenarios, verified in cycle 5.

## Components and interfaces it touches

kernel/plugins (`kernel-plugins.declared`, `plugins-stage`), kernel/bus
(`catalogue`); the configuration read with the kernel's shared code (slice
01's ED-13).

## Done when

kernel/plugins' unit tests cover each refusal and the default time limit;
`wasm/test.sh` writes a passing result for each scenario above; ticket 01's
results unchanged.
