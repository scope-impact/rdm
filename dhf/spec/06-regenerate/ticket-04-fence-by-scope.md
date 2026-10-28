# Ticket 04: Fence one context, component or slice

Slice: `06-regenerate` (`plan.md`, Scope In: "Fence: ... for the project, a
context, a component or a slice"); `fence.feature`, Rule "A clean room can
rebuild the project, one bounded context or one component";
`13-slice-development/fence.feature`; the glossary's "Scope". Builds on
ticket 03.

## What it delivers

regeneration/fence's `run` with `--context C,...`, `--component C/N,...`
and `--slice S` (G-13 for combining them):

- The cut's model read with architecture/model on the cut extracted
  (ED-03): its bounded contexts, the kernel as the context `kernel`, its
  components; under the slices layout, the components a slice reaches,
  every one owning a feature file in its folder, wherever its spec path is
  (ED-05).
- Each scope's implementation found by the configuration's
  `:code-layout`, `{context}` and `{component}` filled in (a component's
  `-` written as the layout writes it), every implementation file starting
  with one of its entries: the code, its unit tests and its step
  definitions. Only those are denied; every other implementation file at
  the cut is kept, unchanged.
- A component the cut's model no longer names, whose files the code
  layout still finds, fenced by them and named in the result as a removed
  component (spec-0329; G-08); a context or component the model does not
  name, whose files the layout finds none of, refused, exit 2, naming it
  and the model's bounded contexts and kernel (spec-0185); a slice the cut
  does not hold refused, exit 2, naming it (spec-0397).

## Scenarios it satisfies

- `@spec-0183` A context scope removes that context's code and keeps the rest, `fence.feature`
- `@spec-0184` A component scope removes that component's code and keeps the rest, `fence.feature`
- `@spec-0226` A list of scopes removes the code of each, and keeps the rest, `fence.feature`
- `@spec-0329` A component the cut removed is fenced by the files the code layout finds for it, `fence.feature`
- `@spec-0185` A scope the model does not name is refused, `fence.feature`
- `@spec-0222` The kernel is a scope like a bounded context, `fence.feature`
- `@spec-0397` A slice scope removes the code of every component the slice reaches, `13-slice-development/fence.feature`

7 scenarios, verified in cycle 7.

## Components and interfaces it touches

regeneration/fence (`run`), architecture/model (`read`, `owners`, as slice
04 built them), regeneration/cut (`extract`).

## Done when

regeneration/fence's unit tests cover each code-layout entry filled in, the
kernel, a removed component, an unknown scope and a slice reaching a
component whose spec path is in another slice; `wasm/test.sh` writes a
passing result for each scenario above; earlier tickets' results
unchanged.
