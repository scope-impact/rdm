# Decisions: slice 02-write-the-spec

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `check.feature`, Scenario: A tag that is not an id, a level, a need or @wip fails the spec check (spec-0150)

Decided by the event storm's hotspot H4 (dhf/storm/2026-10-24-rdm.md):
there is no persona level.

### `check.feature`, Rule: The spec never names an implementation

The spec never names its stack: a spec that names one has become a
description of one implementation, and regenerating from it measures
nothing (loyly:docs/wow/invariants.md@17d68d2, INV-2). The stack lives in
dhf/harness/stack.md.

### `parser.feature`, Rule: The spec is read at a revision

The parser is ours. A construct it does not know is refused, never skipped:
a scenario the parser silently drops is a requirement nothing checks.
The checklist and ratify read the spec at a cut, not the working tree
(cycle 00, dhf/cycles/00/proposed-spec-changes.md, C20).

