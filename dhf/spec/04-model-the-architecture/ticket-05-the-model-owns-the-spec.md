# Ticket 05: The model owns the spec: each feature file found from the model

Slice: `04-model-the-architecture` (`plan.md`, Scope In: "a component's
feature file found from the model"; "the model owns the spec");
`dhf/spec/13-slice-development/plan.md`; ADR 0004, ADR 0005. Regression
items: spec-0392 to spec-0394 sit in slice 13, and they are the model's, so
building architecture/model builds them. Builds on ticket 03.

## What it delivers

- architecture/model, `architecture-model.owners`, over the feature files of
  the spec's folder (ED-10), by the spec's layout:
  - contexts (no `:spec-layout`): a component owns the file its spec path
    names, `<spec dir>/<spec>.feature`, never a file found any other way.
  - slices (`:spec-layout :slices`): a slice is a folder `<NN>-<name>` of the
    spec holding one feature file per component it reaches, each found by its
    name in the model; a component owns every scenario of the feature files
    named for it, in every slice. Findings, each naming what spec-0393's row
    names: `slice-component-unknown` (a file named for no component),
    `slice-missing` (a feature file outside every slice),
    `component-name-shared` (two components sharing a name, naming both),
    `component-unspecified` (a component no slice's file is named for).
- architecture/lint, under the contexts layout: `a-scenario-owner`, a
  component that owns no feature file (naming the component) and a feature
  file no component owns (naming the file); under the slices layout, the
  slice findings above in its place.

## Scenarios it satisfies

- `@spec-0076` A component's spec path names its feature file, `model.feature`
- `@spec-0013` A component that owns no feature file fails the lint, `lint.feature`
- `@spec-0014` A feature file that belongs to no component fails the lint, `lint.feature`
- `@spec-0392` A slice is a folder of the spec, holding its plan and one feature file per component it reaches, `13-slice-development/model.feature`
- `@spec-0393` A slice the model cannot read apart is a finding (4 rows), `13-slice-development/model.feature`
- `@spec-0394` A configuration with no :spec-layout reads the spec by context, as before, `13-slice-development/model.feature`

6 scenarios: 4 verified in cycle 3; spec-0392 and spec-0394 say which
component owns a scenario, which this build shows through no task until
trace, and are pending until cycle 6 (`pending.md`). Their check half (exit
0, no ownership finding) is unit-tested and run here.

## Components and interfaces it touches

architecture/model (`owners`), architecture/lint (`lint`).

## Done when

architecture/model's unit tests cover both layouts, every row of spec-0393,
and a file of each layout's ownership; `sh wasm/rdm-spec check` in this
repository (slices layout) names no ownership finding; `wasm/test.sh` writes
a passing result for each scenario above but spec-0392 and spec-0394, which
it runs and reports as they come out; earlier tickets' results unchanged.
