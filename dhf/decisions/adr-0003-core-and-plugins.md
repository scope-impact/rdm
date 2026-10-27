# ADR 0003: A core harness, and plugins each project composes

> Status: Accepted, 2026-10-27, by the spec owner. Carried into the spec by
> spec-25.

## Context

rdm-spec is the harness other projects are built with (ADR 0002). Running it on
a second project, Löyly (cycles 01 to 04), showed that each project brings
judges of its own: Löyly's affordance contract and the conformance check that
holds every design to it (`dhf/raw/affordance-contracts.md`); rdm-spec's own
held-still oracle; an optional domain model that only a project that storms its
domain keeps. Each was either built into the core (`architecture/domain`, the
`:oracle` gate) or bolted onto a check (`:serves`'s probe). Putting a project's
judge in the core grows the core with every project's idea, and the idea then
cannot evolve with the project that had it. Halla Health's affordance contract
already has a different shape from Löyly's, and both will keep changing.

## Decision

### The core and its published language

- **The core** is what every project needs, the same way: the spec format,
  the tasks (check, stamp, views, cut, scopes, fence, verify, checklist, score,
  scoreboard, observe, status, trace, release, publish, audit), the results
  contract and the task table.
- **The core's events are its published language.** `events.edn` already
  declares each event, its one publisher, what it carries and its shape. A
  plugin reads those, and nothing of the core's internals.

### A plugin is a component the project owns

- **A plugin is a command the project keeps** in a kept path
  (`dhf/harness/plugins/<name>/`), so no builder ever writes or regenerates it.
  It is declared in the configuration's `:plugins`, in the order it runs.
- **It either subscribes or answers.** A subscriber (`:subscribes [:SpecParsed]`)
  is a policy: whenever the core publishes that event in a task, the plugin
  receives it and reports. An answerer (`:answers :Gate` or `:answers :Check`)
  is asked by the core: a Gate before fence and verify, a Check as one of
  verify's checks.
- **One interface for every plugin:** the event or the request as EDN on its
  standard input; its working directory the project's root (the checkout's,
  in verify); `RDM_SPEC` set; its own time limit; exit 0 holds, 1 findings,
  2 cannot run, a timeout named as one with its output kept; findings as
  `FILE:LINE: RULE: what` lines, the shape every core finding has.

### Shared as templates, owned by each project

- **Plugins are shared as templates, never as a running dependency.** rdm-spec
  carries a registry (`plugins/`); `rdm-spec plugin add NAME` copies one into
  the project and records its origin. The copy is the project's: it evolves
  with the project, held still within it (a reviewed commit, pinned by the
  cut). Copies drift on purpose, and `origin.edn` makes the drift visible. An
  idea worth having back returns to the registry by a person's proposal.
- **The rule of two.** A judge lives in one project first. It becomes a
  template when a second project wants a starting point like it. It enters the
  core only if it stops being project-shaped: every project needs it, the same
  way.

### What moves now

- **The domain model leaves the core.** `architecture/domain` is used by no
  project and has one opinionated shape; its rules become the `ddd-domain`
  template.
- **The oracle becomes a Gate plugin** of rdm-spec's own project.
  `rdm-spec oracle` stays as a shorthand for running it.
- **Löyly's conformance check stays Löyly's.** Its first version becomes the
  `affordances` template; whether Löyly keeps running it is Löyly's own
  decision (its spec-5), since scenarios and a black-box grader also cover
  discoverability.
- **EDN everywhere** the core reads or writes: the configuration, the events on
  a plugin's input. A plugin's own files are its own business.

## Consequences

- The core shrinks (the domain model leaves) and stops growing with each
  project's idea.
- A change to an event's contract reaches the plugins that subscribe to it,
  as it reaches the core's own subscribers, so a release can say which plugins
  a change affects.
- A plugin in another language needs an EDN reader.
- Projects share ideas by copying, so one project's change never alters
  another's verdicts.
- The oracle judges only what a spec change did not touch (`--from`, `--to`);
  the graders judge the rest.

## The picture

```
 rdm-spec CORE ── events.edn (published language) ── plugin interface
       │ subscribes (policies)              │ answers (Gate, Check)
       ▼                                    ▼
 REGISTRY (templates: copy once, then own) ── oracle · ddd-domain · affordances
       │ copy, own                          │ copy, own
       ▼                                    ▼
 a project's dhf/harness/plugins/…      another project's, evolving its own way
```
