# ADR 0002 — Slices with Gherkin acceptance

> Status: Accepted (Design Review 75). Supersedes, in part, ADR 0001's "Tests +
> Allure tags are the executable behaviour spec": the spec is Gherkin again,
> for slices.

## Context

ADR 0001 dropped Gherkin: "The test IS the acceptance criterion / scenario.
There is no `.feature` file and no step registry to keep in sync with the code."
The maintainer has since chosen to align RDM's record with Löyly's layout, with
Gherkin scenarios as the acceptance criteria
(`dhf/raw/2026-10-maintainer-requests.md`, 2026-10-24). Löyly treats the spec,
not the code, as the asset: complete enough to rebuild the code from, and
measured by doing so (`loyly/README.md`, `loyly/docs/wow/way-of-working.md`).
A spec held only in tests cannot be that, because the tests are part of what is
rebuilt.

## Decision

- New work is planned as **slices**, `dhf/plan/NN-slug/`: a `plan.md` (goal,
  references, scope, status, tickets), a `spec.feature` and a `decisions.md`.
- In a slice's spec, a **`Rule:` is a design input**: tagged with its id
  (`@DI-n`), the user need it traces to (`@UN-n`) and the bounded context that
  owns it (`@context-<name>`); its title is the design input's text.
- A **scenario is an example that accepts its Rule**, stamped with a stable item
  id (`@item-NN-SSS`). A design input is verified when every scenario under it
  passes in an acceptance run.
- Sources the record cites are kept, immutable, under `dhf/raw/`.
- What ADR 0001 kept stands: user needs are defined once in the V&V plan,
  design inputs are the release-gate denominator, the traceability matrix is
  generated, and the reviewed PR is the approval.

## Consequences

- RDM's reader holds design inputs from two places until the existing contexts
  move into slices (slice 01, decisions D5); one id declared in both is a
  duplicate.
- The step registry ADR 0001 avoided comes back: scenarios run through step
  definitions, and a step that drifts from its scenario is a failing run, not a
  silent one.
- `dhf/AGENT_WORKFLOW.md`, `CLAUDE.md`, the `rdm init`/`adopt` templates and
  the manual describe the old loop until a later slice changes them.
