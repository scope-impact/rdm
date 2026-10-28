# Ticket 03: The C4 lint: the checklist a machine can judge

Slice: `04-model-the-architecture` (`plan.md`, Scope In: "The lint: the C4
checklist"); `lint.feature`, Rule "The C4 model passes the C4 lint", and
`decisions.md`, why its rules are as they are. Builds on tickets 01 and 02.
`dhf/spec/model.edn`: :architecture/lint "Checks" :architecture/model.

## What it delivers

architecture/lint, `architecture-lint.lint` and `for-a-person`, check's
model-lint stage, for every spec, over a model read whole:

- `c4-scope`: a view with no description, naming the view.
- `c4-element-described`: an element with no description, naming it.
- `c4-arrow-labelled`: a relationship with no description, naming both ends.
- `c4-context-actors`: a context view that includes no person, naming the
  view.
- `c4-container-runs`: a container whose kind is neither runs nor stores,
  naming it.
- `c4-events-named`: a relationship publishing an event the catalogue does
  not declare, naming the relationship and the event.
- `c4-view-size`, a warning: a view showing more elements than the
  configuration's `:c4 :view-element-limit` (20 when unset), counted as
  `architecture-model.holds` gives them, naming the view and both counts.
- `c4-description-length`: a person's or a system's description longer than
  `:c4 :description-max-chars` (45 when unset), naming it and both lengths.
- Reported: `{:lint {:for-a-person [...]}}` with the three items of
  spec-0010, exactly, in order, beside the counts ED-06 settles.

Each rule an element breaks is its own finding (`dhf/spec/README.md`).

## Scenarios it satisfies

- `@spec-0001` A view with no description fails the lint, `lint.feature`
- `@spec-0002` An element with no description fails the lint, `lint.feature`
- `@spec-0003` An unlabelled relationship fails the lint, `lint.feature`
- `@spec-0005` A context view with no person fails the lint, `lint.feature`
- `@spec-0006` A container that neither runs nor stores fails the lint, `lint.feature`
- `@spec-0007` A relationship that publishes an unknown event fails the lint, `lint.feature`
- `@spec-0008` A view with more elements than the configured limit warns, `lint.feature`
- `@spec-0009` A person or system description longer than the configured limit fails the lint, `lint.feature`
- `@spec-0010` The lint prints the checklist items no machine can judge, `lint.feature`

9 scenarios, verified in cycle 3.

## Components and interfaces it touches

architecture/lint (`architecture-lint`, `model-lint-stage`),
architecture/model (`read`, `holds`).

## Done when

architecture/lint's unit tests cover every rule above on model values, a
model that breaks none giving no finding, and the limits' defaults;
`sh wasm/rdm-spec check` in this repository names no finding of these
rules; `wasm/test.sh` writes a passing result for each scenario above;
earlier tickets' results unchanged.
