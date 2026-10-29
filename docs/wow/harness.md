# The agent harness

How an agent works under this method without drifting. Löyly's two ideas
carry it (`raw/loyly/docs/wow/harness.md`): **plan and implement are separate
invocations**, and **"done" is ratified, never self-asserted**. Three roles
are added that Löyly leaves to a person: the planner's spec-gap rule, the
grader's black box, and the reviewer. Cites `docs/wow/way-of-working.md`
(the cycle) and `docs/wow/invariants.md`.

## 1. The guardrails

Each exists because agents drift in a specific, observed way. Two questions
sort them: is the guardrail input to a round, or pointed at what the round
produced; and does it say correct or incorrect, or hand back material and
leave the verdict to the agent (Löyly, section 3).

| Guardrail | What it does | Enforced by |
|---|---|---|
| Spec-lock | an edit a builder makes to `spec/`, `docs/` or `harness/` during a build round is reverted; the spec changes in planning rounds, on purpose | the fence's proof, and `verify` refusing a room whose spec tree differs from the cut's |
| Orient step | every round starts by re-reading the sources of truth in the read order below, never by resuming from memory | the brief; the builder's first commit names what it read |
| Spec alone | no planner, builder or grader learns behaviour from any implementation; a silence a grader could see is a spec gap, stated before the build or left to the builder by name | the room holds no earlier implementation of the scope (INV-4, the fence); the planner's gap list |
| Scope fence | an edit outside the scope's implementation paths is reverted | the fence lists what the room keeps; `verify` refuses a room whose kept paths changed |
| Ratified DONE | a DONE claim counts only when `harness verify` passes | INV-5 |
| Black box | a grader sees the built product and the spec, never the builder's tests or code | the grading room: the room's build artefact and the cut, nothing else |
| Adversarial review | every cycle, a reviewer hunts invented rules and spec drift in the diff; it never grades | the review step of the cycle |
| Owner's decisions | a verdict, a spec-gap decision and a cycle's acceptance are a person's | INV-10; `score` refuses a record whose owner is an agent |

## 2. Read order (the orient step)

Give an agent this, in this order, before any task:

1. `README.md`: what this repository is and what it is for.
2. `docs/wow/`: this method (`way-of-working.md`, `invariants.md`, this file).
3. `docs/wiki/`: the domain, from its index.
4. For a product: its `spec/` (storm, model, feature files) at the cut, and
   the plan being worked.

Then the task and the phase (plan, build, grade, review). `raw/` is
background: an agent may read it to check a trace and never edits it.

## 3. The planner

A planning invocation writes or updates a plan and its tickets. It reads the
spec at the cut and the wiki, and **nothing of any implementation**. It is
told: plan only, build nothing.

The plan names the goal, the wiki pages it rests on, the scenarios it
delivers (by id), the tickets in order, and its **spec gaps**: every place
the spec is silent where a grader could see the answer. Each gap is marked:

- **visible**: the spec must state it before the build; the plan is not
  accepted until it does;
- **free**: the builder's choice, named so the grader does not fail it.

A planner that resolves a gap by looking at how a previous build behaved has
failed the round: that is behaviour learned from an implementation, and the
spec stays silent while the build "passes".

## 4. The builder

One builder per cycle, in the room `harness fence` made, which holds the cut
and the implementation outside the scope, and nothing of the scope's earlier
implementation, no measurement, no golden.

**Read first:** the plan, then the scenarios it delivers, then the wiki pages
it cites, then the model and the storm.

**RED/GREEN discipline.** The loop runs under an externally tracked state,
and the state overrides everything else about what to do next:

- GREEN: write the next failing test from a scenario; implement nothing.
- RED: write the minimum that passes; write no new test.
- Refactor only when green, then commit.
- Retroactive tests are forbidden: implementation before test is a failed
  round, not a shortcut.

The builder keeps `redgreen.log` in the room: one line per transition, the
scenario id and the commit. Its own suite green is its claim; `harness
verify` is the state.

**Writing the tests.** Every test names the scenario it implements by its id,
and the suite writes one result per scenario run in Allure's result model
into the results folder the configuration names, labelled `item = <id>`,
with the tool's attachments (a trace, a screenshot, a transcript) beside it.
The tool is the builder's choice: a Gherkin runner, a unit framework, a
browser driver, a script. The gate reads the results, never the tool
(`docs/wow/way-of-working.md`, "Results are tool-neutral"). A test that
writes no result does not exist to the gate.

**Where it may write:** the scope's implementation paths and its tests. Not
`spec/`, not `docs/`, not `harness/`, not outside the room.

**Decisions.** Every choice the spec left free, and every divergence from the
plan, goes in the room's `decisions.toml` with the pruned branch named. The
owner accepts or refuses each one with the cycle.

**Report**, under thirty lines: what was built, by ticket; what the suite
runs; every decision; every scenario it could not make green, and why.

## 5. The grader

A grader works black box: in its own room with the built product and the cut,
never the builder's tests or code. For every checklist item it drives the
product the way its actor would, records `pass` or `fail`, and gives the
evidence: what it did and what it saw. An item it cannot reach is `fail`
with the reason; it never skips one. A deterministic item is judged byte for
byte against its Then; a `@nondeterministic` item is judged by shape at each
placeholder (the checklist names them and the spec declares their shapes)
and byte for byte everywhere else, and the evidence records the value seen.
Where the placeholder is judged (the checklist names its judge), the grader
runs the judge, a plugin answering `item`, on the outcome it observed and
files the judge's verdict and evidence as its own; a person may overrule it
in their own verdict, and the owner's verdicts are what the judge learns
from next (decision 0002). It writes one `verdicts-<name>.toml`
and nothing else. Several graders are scored apart and agreed by majority;
a builder never grades its own build.

## 6. The reviewer

A reviewer reads the room's diff against the plan and the spec, hunting
invented rules (behaviour no scenario states), spec drift (a scenario read
loosely), and stack words in anything under `spec/` or `docs/`. It writes
`review.md`: findings, each citing the line and the scenario. It never
grades and never fixes.

## 7. The integrator

The integrator runs the cycle's machinery (cut, scopes, fence, verify,
checklist, score, scoreboard) and files the records. It makes no decision the
cycle marks as the owner's: it does not accept a plan, a spec gap or a build,
and does not write a verdict. When the owner is not available, the cycle
waits.
