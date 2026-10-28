# Decisions: slice 13-slice-development

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

None yet.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `check.feature`, Rule: Every slice is planned

spec-30, from cycle 23: spec-29's several spec folders were one rule in
parser.feature but reached eight components more; the scopes named six
and the integrator widened them by hand (dhf/cycles/23/cycle.edn). The
spec owner chose Löyly's slice (loyly:README.md@17d68d2, "How to read the
repo", layer 4): a piece of behaviour, planned, and rebuilt whole.

### `check.feature`, Rule: A slice says where it stands, and what was decided in it

spec-33, ADR 0005: the slice is the unit of work (Löyly,
loyly:docs/wow/way-of-working.md@17d68d2, sections 1, 2 and 6).

### `checklist.feature`, Rule: A slice is graded whole

spec-30, from cycle 23: spec-29's several spec folders were one rule in
parser.feature but reached eight components more; the scopes named six
and the integrator widened them by hand (dhf/cycles/23/cycle.edn). The
spec owner chose Löyly's slice (loyly:README.md@17d68d2, "How to read the
repo", layer 4): a piece of behaviour, planned, and rebuilt whole.

### `checklist.feature`, Rule: A slice's checklist tells its own scenarios from its regressions

spec-33, ADR 0005: the slice is the unit of work (Löyly,
loyly:docs/wow/way-of-working.md@17d68d2, sections 1, 2 and 6).

### `fence.feature`, Scenario: A slice scope removes the code of every component the slice reaches (spec-0397)

spec-30: a slice is rebuilt whole (scopes.feature).

### `model.feature`, Rule: Under the slices layout, the spec is grouped by slice, then by component

spec-30, from cycle 23: spec-29's several spec folders were one rule in
parser.feature but reached eight components more; the scopes named six
and the integrator widened them by hand (dhf/cycles/23/cycle.edn). The
spec owner chose Löyly's slice (loyly:README.md@17d68d2, "How to read the
repo", layer 4): a piece of behaviour, planned, and rebuilt whole.

### `release.feature`, Rule: A release can be asked of one slice

spec-33, ADR 0005: the slice is the unit of work (Löyly,
loyly:docs/wow/way-of-working.md@17d68d2, sections 1, 2 and 6).

### `scopes.feature`, Rule: A change rebuilds every slice it touches, whole

spec-30, from cycle 23: spec-29's several spec folders were one rule in
parser.feature but reached eight components more; the scopes named six
and the integrator widened them by hand (dhf/cycles/23/cycle.edn). The
spec owner chose Löyly's slice (loyly:README.md@17d68d2, "How to read the
repo", layer 4): a piece of behaviour, planned, and rebuilt whole.

### `scopes.feature`, Scenario: A component with feature files in two slices is reached by both (spec-0416)

spec-33, from spec-32's scopes under cycle 24's release: the slices
11-project-in-a-folder and 12-several-specs were listed reaching no
component, and 08-plugins without verify: a component was found by its
one spec path in the model, never by the other feature files named for
it. Cycle 24's graders passed spec-0398 on fixtures whose components had
one feature file each.

### `scorer.feature`, Scenario: A cycle may be scoped to a slice (spec-0396)

spec-30: a slice is rebuilt whole (scopes.feature).

### `scorer.feature`, Rule: A slice cycle is scored for its slice, and for what it regressed

spec-33, ADR 0005: the slice is the unit of work (Löyly,
loyly:docs/wow/way-of-working.md@17d68d2, sections 1, 2 and 6).

### `status.feature`, Rule: Status reports each slice

spec-33, ADR 0005: the slice is the unit of work (Löyly,
loyly:docs/wow/way-of-working.md@17d68d2, sections 1, 2 and 6).

### `verify.feature`, Rule: A slice-scoped verify answers for the components the slice reaches

spec-32, from cycle 24: a slice-scoped verify required a result for every
scenario, as a whole-project one does; spec-0389 narrowed a spec scope,
nothing a slice scope. A slice scope rebuilds the components it reaches
(ADR 0004), and those components' scenarios are what it answers to.

