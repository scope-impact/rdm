# Ticket 02: The views: generated from the one model, rendered, fresh on content

Slice: `04-model-the-architecture` (`plan.md`, Scope In: "The views: one
source, each level holding what C4 says it holds"; "views stale on content,
never formatting"); the glossary's "View" and "Model hash". Builds on ticket
01. `dhf/spec/model.edn`: :architecture/views "Generates views from"
:architecture/model, and "Writes views to" the repository.

## What it delivers

- architecture/model, `architecture-model.holds`: what each level of view
  holds, as spec-0198's rows say: a context view the system, every person,
  every external system and the relationships between them (a container's or
  component's relationship drawn as the system's); a container view every
  container, person and external system and their relationships; a component
  view one context's components inside its boundary and the elements outside
  it they relate to, drawn as external; a dynamic view the elements its steps
  name and its steps, in order.
- architecture/views, `architecture-views.source`, `generate` and `run`, the
  task `rdm-spec views`: for every spec the configuration declares (ED-04),
  each view's diagram source written to `dhf/views/<key>` and rendered beside
  it, in the formats the stack names, by the renderer run through
  `rdm:host/process` (ED-03); each output's first line a comment, in its
  format, holding `model-sha256: ` and the model's hash; the result as ED-06
  settles. A model that cannot be read: its findings, exit 1, nothing
  written. The renderer missing or failing: cannot-run, exit 2, nothing
  written (ED-03).
- architecture/views, `architecture-views.freshness` and `views-stage.run`:
  every output of every view that does not record the model's current hash,
  or does not exist, one finding `view-stale` naming it (ED-06); none for a
  model that cannot be read (ED-07). The hash is the model's content, so
  comments and whitespace change nothing.
- The Givens "views generated from the model" and "a rendered view whose
  recorded hash differs from the model's" run this build's `views` (slice
  01's ED-12).

## Scenarios it satisfies

- `@spec-0017` Every view in the model is generated and rendered, `views.feature`
- `@spec-0198` A view of a level holds exactly its elements (4 rows), `views.feature`
- `@spec-0018` A view older than the model fails the check, `views.feature`
- `@spec-0077` Reformatting the model leaves its views fresh, `model.feature`
- `@spec-0078` Changing any value makes the views stale, `model.feature`

5 scenarios, verified in cycle 3. With the views' freshness built, slice
01's spec-0235 (`01-start-a-project/pending.md`, "Unblocked in cycle 3") is
verified in this cycle too, as a regression item.

## Components and interfaces it touches

architecture/views (`architecture-views`, `views-stage`, `tasks`),
architecture/model (`holds`, the hash), the host (`rdm:host/process`, `run`).

## Done when

- architecture/model's unit tests cover each level's elements and
  relationships on a model of every kind of element; architecture/views'
  unit tests cover the source of each level, the header line in both
  formats, and freshness on a fresh, a stale and a missing output, with the
  renderer stood in for.
- `sh wasm/rdm-spec views` in this repository writes every view of
  `dhf/spec/model.edn`, and `sh wasm/rdm-spec check` then names no stale
  view.
- `wasm/test.sh` writes a passing result for each scenario above, every row
  of spec-0198 included, and for slice 01's spec-0235; ticket 01's results
  unchanged.
