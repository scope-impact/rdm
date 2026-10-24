# Slice 01: The harness

status: ready-for-development

## Goal

A "done" claim about RDM is ratified by a check its builder does not control, and
how much of the spec a build carries is measured, cycle over cycle. RDM gains a
harness, specified before it is built: the spec is read as the record's design
inputs, every scenario carries a stable id, a checklist is derived from the spec
alone, cycles are scored with honest denominators and compared both ways, and a
gate checks its own instrument, runs the suite through one declared command,
starts the served graph inside a budget, proves it is this record, and refuses a
build round that changed the spec.

## References

Per the minimum reference rule (Löyly's `docs/wow/way-of-working.md` §3), this
slice rests on:

- `dhf/raw/2026-10-maintainer-requests.md`, 2026-10-24 (build a harness for RDM,
  via spec; align the DHF with Löyly's layout and Gherkin acceptance)
- `loyly/harness/README.md` (the gate, the instrument, the failure modes each
  guard was learned from) and `loyly/harness/spec.feature` (the stamper, the
  checklist, the scorer and the scoreboard, specified; the boot budget)
- `loyly/harness/verify.sh` (the gate's steps: the instrument first, the port,
  the suite through a contract, the boot budget, the identity probe)
- `loyly/docs/wow/harness.md` (ratified DONE, spec-lock) and
  `loyly/docs/wow/invariants.md` (enforcement points)
- `dhf/documents/verification_and_validation_plan.md`, UN-020
- `dhf/decisions/adr-0002-gherkin-slices.md` (why the spec is Gherkin now)
- `ddd-workshop/` (PensionBee's DDD workshop: EventStorming, command handlers,
  success and fail events, policies) and `CONTEXT.md`, "Domain model" (RDM's
  adaptation of it)

## Scope

**In:**

- The record reads design inputs from slices' specs (DI-90)
- The stamper (DI-91), the checklist (DI-92), scoring (DI-93), the scoreboard
  (DI-94)
- The gate: the instrument checked first (DI-95), the suite through one declared
  command (DI-96), the served graph inside a boot budget (DI-97), the identity
  proof (DI-98), spec-lock (DI-99)
- One declared configuration (DI-100)

**Out:**

- The fence, deleting the code and rebuilding it from the spec: see
  `decisions.md` D4
- Carrying Löyly's harness byte for byte as a member of its ring: see D1
- Moving the existing eight contexts' design inputs into slices: a later slice
  (D5)
- A perception step (an unjudged look at the running product): D6

## Commands and events

Found by EventStorming the harness, after the PensionBee DDD workshop
(`ddd-workshop/`, branch `02-eventstorming`; RDM's own terms in `CONTEXT.md`,
"Domain model"): an actor issues a command, resulting in its success event or a
fail event (the business rule broken, after the slash), which affects an entity.

| Actor | Command | Success event | Fail events | Entity |
|-------|---------|---------------|-------------|--------|
| Contributor, or the commit hook | Check design controls (DI-90 adds four rules) | Design Controls Approved | Not Approved / Untagged Rule · Rule Without Owner · Rule Without Need · Unreadable Spec · Duplicate Id | The record |
| Maintainer or agent | Stamp scenarios | Scenarios Stamped | Not Stamped / No Spec | Spec |
| Maintainer, agent or CI | Derive checklist | Checklist Derived (warned: Planned Slice Skipped) | Not Derived / Unstamped Scenario · Empty Spec | Checklist |
| Maintainer or CI | Score cycle | Cycle Scored | Not Scored / No Verdicts · Unknown Item | Cycle |
| Maintainer | Compare cycles | Cycles Compared | — | Scoreboard |
| Builder (agent or person) or CI | Admit round | Round Admitted | Not Admitted / Spec Changed · Unknown Setting · No Suite Declared | Build round |
| — (reaction) | Check instrument | Instrument Checked | Not Checked / Wrong Known Answer | Build round |
| — (reaction) | Run suite | Suite Passed | Not Passed / Suite Red | Build round |
| — (reaction) | Start served graph | Served Graph Answered | Not Started / Port Taken · Boot Budget Exceeded | Build round |
| — (reaction) | Prove identity | Identity Proven | Not Proven / Not This Record | Build round |

**Reactions** (the workshop's *policies*; RDM keeps "policy" for the risk
policy): whenever a round is admitted, the instrument is checked; whenever the
instrument is checked, the suite runs; whenever the suite passes, the served
graph is started; whenever it answers, identity is proven. Together they are
"harness verify": its verdict is *Build Ratified* when *Identity Proven*
happens, else *Build Not Ratified*, naming the fail event (D9).

**Entities:** a spec and its scenarios (ids stamped once), a cycle (its number,
its checklist, a verdict per item), a build round (its base commit, its events).
**Read models**, derived and never edited: the checklist (from the specs), the
scoreboard (from two cycles).

## Acceptance

`spec.feature` in this directory: 11 Rules, one design input each, accepted by
37 scenarios. Every scenario must pass in an acceptance run before this slice is
done, and the gate of DI-95 to DI-99 ratifies it once it exists.

## Tickets

In order; each lands with its scenarios running and the gates green.

1. Reading the spec (DI-90): the record's design inputs from Rules.
2. The stamper and the checklist (DI-91, DI-92).
3. Scoring and the scoreboard (DI-93, DI-94).
4. Configuration (DI-100) and the gate (DI-95 to DI-99).
