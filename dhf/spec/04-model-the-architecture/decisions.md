# Decisions: slice 04-model-the-architecture

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `lint.feature`, Rule: The C4 model passes the C4 lint

The rules below are the C4 skill's quality checklist where a machine can
judge it (agent-skills:skills/engineering/c4-diagrams/reference/c4-guide.md@da65901,
"Quality Checklist").

### `lint.feature`, Scenario: A person or system description longer than the configured limit fails the lint (spec-0009)

Found by the first event storm's render (dhf/storm/2026-10-24-rdm.md):
The renderer draws a person's or system's name over a description that wraps
past two lines.

### `lint.feature`, Rule: Bounded contexts are modules of one container, and the model owns the spec

rdm-spec is a modular monolith: one container whose bounded contexts are
composable modules, each drawn as a boundary around its components.

### `lint.feature`, Scenario: A project that lets its contexts relate by call is not held to events only (spec-0339)

The rule is rdm-spec's own architecture, and it bit the first other
project: the Löyly app's booking had to read the schedule through an
event its spec never names (dhf/cycles/loyly-comparison.md, finding 3).
A project declares how its contexts may relate; events only is the
default, and a project's own design record (a domain model, where its
plugin keeps one) says why.

### `model.feature`, Rule: Views go stale when the model's content changes, never its formatting

A hash of the text would mark every view stale after a reformat that
changed nothing, and a gate that cries wolf is a gate that gets skipped.

