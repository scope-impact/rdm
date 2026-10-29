# The way we work

How a product is built under this harness, and how the harness itself is
kept: who does what, in what order, and what is refused. What each thing
**is** lives in `docs/wiki/glossary.md`; every term below is used as the
glossary defines it and is not redefined here. Löyly's method
(`raw/loyly/docs/wow/way-of-working.md`, sections 0 to 7) with two additions
the intent asks for (`README.md`): the storm is the forced front of the
chain, and regeneration is scoped to a container or a component. Cites
`docs/wow/invariants.md` throughout.

## 1. The one-paragraph version

The spec is the asset; code is a build product of it. Knowledge is distilled
from sources into the wiki and, for a product, into a storm; the model is
derived from the storm; scenarios are written one feature file per component.
At a fixed time the spec is cut. The harness computes the scope, fences a
room for it, one builder regenerates it, the gate ratifies it, and the
instrument scores it against the derived checklist, cycle over cycle.
Anything not written into the spec dies with the implementation.

## 2. The layers, in order

```
KNOWLEDGE      raw/                      cited, never edited
STORM          spec/storm.toml           harness storm        INV-1
MODEL          spec/model.toml           harness model        INV-1
ACCEPTANCE     spec/<component>.feature  harness stamp        INV-6
CUT            tag spec-N                harness cut
SCOPE          computed from two cuts    harness scopes       INV-4
ROOM           cycles/NN/room/           harness fence
BUILD          one builder, in the room
GATE           cycles/NN/verified.toml   harness verify       INV-5, INV-11
MEASURE        cycles/NN/score.toml      harness score, scoreboard   INV-6
DECISIONS      docs/decisions/, docs/plan/NN-slug/decisions.md
```

Each layer is derived from the one above and may not drift from it. The same
names appear at every layer, re-cased per layer; a Gherkin line and a storm
element that disagree about a name are a bug by construction (Löyly,
section 1; the glossary is the list of names).

## 3. No storm, no cut; no plan, no code

A behaviour change starts in `spec/`, never in an editor, in this order:

1. **Storm.** Add the actors, commands and events the change needs, each
   citing a source. A question the change opens is recorded as a hotspot,
   `open`; it must be `decided`, with its decision and a source, before a
   cut.
2. **Model.** Name the component that owns the new element, under its
   container, in its group; declare any technology on the container or
   the deployment node, never in a scenario.
3. **Scenarios.** State the acceptance criteria in the component's feature
   file; run `harness stamp`. A scenario is deterministic unless its outcome
   holds a value the spec cannot pin; then tag it `@nondeterministic`, write
   each such value as a placeholder `<what it is>`, and declare the
   placeholder's shape in `spec/placeholders.toml`.
4. **Plan.** Write or update `docs/plan/NN-slug/plan.md`: the goal, the wiki
   pages it rests on, the scenarios it delivers, the tickets, the spec gaps.

Arriving with a feature idea and no plan, write the plan first (Löyly,
section 2). `harness check` refuses a project whose storm, model or spec
breaks a rule; `harness cut` refuses whatever `check` refuses.

## 4. Every document cites another

A wiki page cites a source; a storm element cites a source or a wiki page; a
plan cites wiki pages and scenarios; a ticket cites scenarios; a decision
cites the plan item it diverged from. A claim that rests on nothing is not
finished, it is filed (Löyly, section 3). `harness storm` enforces the storm's
half; review enforces the rest.

## 5. The firewall

A product's spec and storm, the wiki and the plans name no stack (INV-2).
The stack is written once, in the model's `technology` keys, where C4 puts
it (decision 0003); `harness check` reads them and refuses a feature file or
a storm that names one; review holds the rest. When a spec
turns out to be insufficient to build from, that is a spec bug: fix the spec,
not the reading.

## 6. Work and regeneration are scoped differently

The plan is the unit of work; the component is the unit of regeneration and
the container is its ceiling (glossary: plan, component, container, scope).
The rule that follows: **the scope is read from the cut, never from the
plan.** A plan may reach several components and a component several plans;
`harness scopes` decides what a cycle rebuilds, and a scope written into a
cycle record is refused (INV-4).

## 7. A cycle

One cycle, in order. Each actor is a person or an agent; the machinery
treats both the same. Steps marked **owner** are a person's and cannot be
filled by the integrator (INV-10).

| Step | Who | What | Record |
|---|---|---|---|
| plan | planner, then **owner** accepts | the plan and its tickets, from the spec alone; every spec gap named visible or free | `docs/plan/NN-slug/` |
| cut | integrator | `harness check` green, tree clean, `harness cut spec-N` | tag `spec-N` |
| scope | harness | `harness scopes spec-M spec-N` | the cycle record's `from` and `cut`; nothing else |
| fence | harness | `harness fence NN`: the room, and its proof | `cycles/NN/room/`, `fence.toml` |
| build | one builder | in the room, reading the plan first, test-first, until its own suite is green | the room's commits, `redgreen.log`, `decisions.toml` |
| gate | harness | `harness verify NN`: the suite twice, inside the budget, read from its results; plugins answering `verify` | `cycles/NN/verified.toml`, `cycles/NN/results/run-N/` |
| checklist | harness | `harness checklist` at the cut | `cycles/NN/checklist.toml` |
| grade | graders, each in their own room, black box | one verdict per item, with evidence | `cycles/NN/verdicts-<grader>.toml` |
| score | harness | `harness score NN <verdicts...>` | `cycles/NN/score.toml` |
| scoreboard | harness | `harness scoreboard NN` | printed, filed in the cycle's record |
| review | reviewer | hunts invented rules and spec drift in the diff; never grades | `cycles/NN/review.md` |
| accept | **owner** | on the evidence: the score, the scoreboard, the review, the builder's decisions; brings the build in or refuses it | `cycles/NN/cycle.toml`, `docs/decisions/` |

Three refusals hold across the steps: a builder never grades its own build;
a grader never reads the builder's tests; nobody re-records a golden to get a
green (INV-3).

### What the gate holds a run to

`verify` reads results, never the runner (INV-11). Before each run it empties
the results folder; after each run it copies what the run wrote, attachments
included, into `cycles/NN/results/run-N/`. A run is ratified when, read from
its results alone:

- at least one result was written;
- every result names an item on the checklist and the tool that wrote it;
- every item not tagged `@wip` has a result;
- no result for an item has a status other than `passed`;

and both runs are ratified inside the budget. A runner that exited 0 and
wrote nothing is refused; a runner's exit code is never read.

### Where a monitor's results go

The gate reads only the room's run. A monitor's result is evidence about a
deployment, never about a cut, and a cut is ratified before anything is
deployed, so a result already in the folder before a run is emptied with it
and never enters the cycle. What reads a monitor's results, a status of the
running product against the same checklist, an item passed in the room and
failed in production as a finding, is a plan of its own, written when a
product asks for it, in the same result model against the same item ids.
Nothing new is invented for it: the storm names the events a trace must
show and the checklist names the items a query must answer.

## 8. Decisions

Planning and history are the same structure in opposite directions. Every
divergence between a plan item and what happened is a decision, recorded in
the plan's `decisions.md` with the pruned branch named (Löyly, section 6); a
builder's choices go in the room's `decisions.toml` and the owner accepts or
refuses each with the cycle. Decisions about the harness itself go in
`docs/decisions/`, numbered.

## 9. The harness's own custody

The harness is held still (INV-3). It changes by a person, in a planning
round, against `harness/spec.feature` first: a behaviour change is a scenario
before it is code. Its gate is `cargo fmt --check`, `cargo clippy`,
`cargo test`, and its oracle's goldens once they exist, in the pre-commit
hook and in CI (INV-9). The oracle's comparisons are written as results, in
the same model the gate reads (INV-11).

A golden changes for one of three reasons only (`raw/loyly/harness/README.md`,
the goldens), each named in the commit:

- the harness's behaviour changed, and a scenario states the new behaviour;
- the wording changed and nothing else; the oracle keeps only a finding's
  rule, so wording alone moves no golden;
- the spec grew: the golden's diff is additions only.

Re-recording to get a green is never a reason.

## 10. Mistakes already made, not to repeat

From rdm-spec's 27 cycles (`raw/rdm/retrospective-01-12.md`,
`raw/rdm/docs/wow/way-of-working.md`, "Mistakes already made"), kept here so
they are not made a third time:

- an id written into a commit message from memory instead of the stamper;
- a scope added by hand because a rule "obviously" reached a component;
- a golden recorded by a build nobody accepted;
- a judge installed without moving what it holds still (goldens, pins);
- a diagnosis read from a tool's output against the integrator's own
  expectation rather than the spec;
- a plan "accepted as recommended" by the integrator in the owner's place;
- the harness's own hooks and CI removed in a cleanup, and not missed for
  234 commits.

## 11. Where the rest is

What each thing is: `docs/wiki/glossary.md`. Each rule and its enforcement
point: `docs/wow/invariants.md`. How an agent is pointed at this repository
and the guardrails it runs under: `docs/wow/harness.md`. The domain the
harness serves: `docs/wiki/`, from its index.
