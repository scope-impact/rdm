# Ticket 01: The slice composed: the sources index read, and check's two stages wired

Slice: `03-ground-the-spec` (`plan.md`, Scope In: "Sources: each indexed
with its repository, pins and record"). Build: the component build of ADR
0006 (`dhf/decisions/adr-0006-wasm-components.md`, cycle 4 of its build
order), its stack in the first section of `dhf/harness/stack.md`.
Architecture: `dhf/wit/` (`knowledge-sources.wit`, `knowledge-wiki.wit`,
`slice-03.wit`, `slice-03.wac`, and `events.wit`'s interface
`knowledge-events`; `tasks.wit` and `spec-check.wit` as slices 01 and 02
left them). Builds on slices 01, 02 and 04 as built.

## What it delivers

The whole path through the slice before any citation is resolved:

- `wasm/components/knowledge-sources/` and `knowledge-wiki/`, each building
  to its own component that targets its world
  (`knowledge-sources-component`, `knowledge-wiki-component`), each world
  and `ground-the-spec` given the host's standard interfaces as ED-08
  settles. In this ticket the wiki's stage answers with no finding; ticket 04
  fills it.
- The composition: `wac compose dhf/wit/slice-03.wac` into
  `wasm/build/deps/rdm/ground-the-spec.wasm`, and the command with the slice
  added. `rdm-spec.wac`, as cycles 2 and 3 leave it, gains these lines and
  rewrites none (ADR 0006, "How the command reaches the slices"; ED-02), and
  its comment's "Slices built" gains 03: the slice's instance, above slice
  02's (an instance is declared before it is used),

      let ground-the-spec = new rdm:ground-the-spec { ... };

  the two stage wiring lines inside slice 02's arguments, beside slice 04's
  and ahead of the spread,

      sources-stage: ground-the-spec["sources-stage"],
      wiki-stage: ground-the-spec["wiki-stage"],

  and the tasks wiring line inside the command's arguments, above the
  spread,

      ground-the-spec: ground-the-spec["rdm:spec/tasks@0.1.0"],

  so that slice 02's instance reads

      let write-the-spec = new rdm:write-the-spec {
          model-lint-stage: model-the-architecture["model-lint-stage"],
          views-stage: model-the-architecture["views-stage"],
          sources-stage: ground-the-spec["sources-stage"],
          wiki-stage: ground-the-spec["wiki-stage"],
          ...not-built,
          ...
      };

  The stand-in's spread stays: the clauses, paths and plugins stages are
  still its. `rdm-spec.wit` changes only in its comment: "Slices built" gains
  `03, ground-the-spec (slice-03.wit)`. The planner composed exactly this
  against stand-in components built from the declarations (slices 01, 02 and
  04 as their worlds, the not-built stand-in with `include
  check-stages-not-built;` as slice 02's ticket 01 adds it).
- knowledge/sources' `tasks` (ED-03): no entries, `run` answering none.
- knowledge/sources, `knowledge-sources.index` and `sources-stage.run`: the
  configuration read as ED-10 settles; the sources index read as EDN (one
  that cannot be read, `sources-unreadable`); for each source, in the index's
  order, a source with no pin, with no repository, with no record or a record
  that is not in the raw directory, each its own finding naming the source
  (and the record file), on the source's line of the index; KnowledgeIndexed
  published (`:revision :working-tree`, `:sources`, `:pages`, `:citations`,
  `:findings`), its EDN handed back as the stage's published event, so
  `check --events` lists it first. Rule names as ED-07 settles.
- `wasm/test.sh` extended to the feature files named for this build's new
  components (`sources`, `wiki`, in every slice).

## Scenarios it satisfies

- `@spec-0120` A source with no pin fails the check, `sources.feature`
- `@spec-0270` A source with no repository fails the check, `sources.feature`
- `@spec-0269` A source whose record is missing fails the check, `sources.feature`

3 scenarios, verified in cycle 4.

## Components and interfaces it touches

knowledge/sources (`knowledge-sources`, `knowledge-events`, `sources-stage`,
`tasks`), knowledge/wiki as a shell (`knowledge-wiki`, `wiki-stage`), the
composition (`slice-03.wac`, `rdm-spec.wac`); spec/check's stages as slice
02 built them, unchanged.

## Done when

- `wasm-tools component wit dhf/wit` parses; each crate's component targets
  its world; `wac compose` writes the slice, and the command with the lines
  above.
- `sh wasm/rdm-spec check` in a project set up by `rdm-spec init` exits 0, its
  error stream no longer naming `sources` or `wiki` as not built;
  `sh wasm/rdm-spec check --events` lists KnowledgeIndexed, published by
  knowledge/sources, before SpecParsed; `sh wasm/rdm-spec help` still lists
  every task as spec-0176 writes it.
- knowledge/sources' unit tests cover each finding of the index, and a
  KnowledgeIndexed whose EDN carries the catalogue's keys.
- `wasm/test.sh` writes a passing result for each scenario above, and slices
  01's, 02's and 04's scenarios still pass, spec-0090 among them (the
  skeleton's empty sources index and wiki).
