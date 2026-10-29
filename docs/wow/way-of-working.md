# The way we work

How a product is built under this harness, and how the harness itself is
kept. Löyly's method (`raw/loyly/docs/wow/way-of-working.md`, sections 0 to
7) with two additions the intent asks for (`README.md`): the storm is the
forced front of the chain, and regeneration is scoped to a container or a
component. Cites `docs/wow/invariants.md` throughout.

## 1. The one-paragraph version

The spec is the product; code is a build product of it. Knowledge is
distilled from immutable sources (`raw/`) into a domain wiki (`docs/wiki/`)
and, for a product, into an **event storm kept as data** (`spec/storm.toml`):
actors, commands, events, policies, aggregates, read models and hotspots,
every element citing a source. The **model** (`spec/model.toml`) is derived
from the storm: a component owns storm elements and sits in a container;
contexts relate only through storm events. Scenarios are written in Gherkin,
one feature file per component. At a fixed time the spec is **cut** (tagged).
The harness computes the **scope** of what changed, builds a **clean room**
for it, one **builder** regenerates it, a **gate** the builder does not
control ratifies it, and an **instrument** scores it against a checklist
derived from the spec, cycle over cycle. Anything not written into the spec
dies with the implementation (`raw/loyly/harness/README.md`, "The idea in
five lines").

## 2. The layers

```
KNOWLEDGE        raw/                 immutable sources; never edited, only cited
   |
STORM            spec/storm.toml      actors, commands, events, policies, aggregates,
   |                                  read models, hotspots; every element sourced
MODEL            spec/model.toml      containers, components, relationships;
   |                                  derived from the storm (INV-1)
ACCEPTANCE       spec/<component>.feature   one file per component, in domain language,
   |                                  stamped @item-NNN once (INV-6)
CUT              tag spec-N           a merged spec that check passed
   |
SCOPE            harness scopes       the components a cut changed, closed over their
   |                                  events, up to the container; computed (INV-4)
CLEAN ROOM       harness fence        a clone with the scope's implementation and every
   |                                  measurement removed and the removal proved
BUILD            one builder          in the room, test-first, from the cut alone
   |
GATE             harness verify       the suite green twice inside a budget (INV-5)
   |
MEASURE          harness score,       verdicts against the derived checklist; both
   |             scoreboard           directions, never a net (INV-6)
DECISIONS        docs/decisions/      every divergence from the plan, with the branch
                                      it pruned
```

The same domain names appear at every layer, re-cased per layer: "standing
slot" in the wiki, `StandingSlot` in the storm, `standing-slot` in a feature
file. A Gherkin line and a storm element that disagree about a name are a bug
by construction (Löyly, section 1).

## 3. No storm, no cut; no plan, no code

Behaviour changes start in `spec/`, never in an editor:

1. The storm holds the actors, commands and events the change needs, each
   citing a source under `raw/`. A hotspot the change opens is recorded as
   `open`, and a cut is refused until it is `decided` with its decision and
   its source (INV-1).
2. The model names the component that owns the new element, and the
   container it sits in.
3. The component's feature file states the acceptance criteria in domain
   language; `harness stamp` gives each scenario its id.
4. A plan (`docs/plan/NN-slug/plan.md`) names the goal, the wiki pages it
   rests on, the scenarios it delivers and the tickets that deliver them.

An agent or a person arriving with a feature idea and no plan writes the
plan first (Löyly, section 2). `harness check` is the enforcement: it refuses
a project whose storm, model or spec breaks a rule, and `harness cut` refuses
whatever `check` refuses.

## 4. Every document cites another

A wiki page cites the raw source it distills; a storm element cites a raw
source or a wiki page; a plan cites wiki pages and scenarios; a ticket cites
the scenarios it implements; a decision cites the plan item it diverged from.
A claim that rests on nothing is not finished, it is filed (Löyly, section 3).
`harness storm` enforces the storm's half; review enforces the rest.

## 5. The firewall

`spec/`, `docs/wiki/`, `docs/plan/` and `docs/wow/` name no language,
framework, database, hosting, vendor or toolkit. `raw/` is exempt: it is
source, not spec. `harness check` holds a word list (INV-2); review holds the
rest. When a spec turns out to be insufficient to build from, that is a spec
bug: fix the spec, not the reading.

## 6. The unit of work and the unit of regeneration

They differ, and the difference is kept explicit:

- **A plan is the unit of work.** It is planned, specified, built, verified
  and closed, and its decisions are its own.
- **A component is the unit of regeneration, and a container is its ceiling.**
  A cycle rebuilds the components a cut changed, closed over the events they
  publish and consume; a change that moves a component between containers,
  and every Nth cycle, rebuilds the whole container (INV-4).

A plan may reach several components; a component may be reached by several
plans. The scope is read from the cut, never from the plan.

## 7. A cycle

One cycle, in order. The actor in each step is a person or an agent; the
machinery treats both the same. Steps marked **owner** are a person's and
cannot be filled by the integrator (INV-10).

| Step | Who | What | Record |
|---|---|---|---|
| plan | planner, then **owner** accepts | the plan and its tickets, from the spec alone; spec gaps named as visible (must be stated before the build) or free (the builder's choice) | `docs/plan/NN-slug/` |
| cut | integrator | `harness check` green, tree clean, `harness cut spec-N` | tag `spec-N` |
| scope | harness | `harness scopes spec-M spec-N` | the cycle record's `from` and `cut`; nothing else |
| fence | harness | `harness fence NN`: the room, and its proof | `cycles/NN/room/`, `fence.toml` |
| build | one builder | in the room, reading the plan first, test-first, until its own suite is green | the room's commits, `redgreen.log` |
| gate | harness | `harness verify NN`: the suite twice, inside the budget, read from its results (below); plugins answering `verify` | `cycles/NN/verified.toml`, `cycles/NN/results/run-N/` |
| checklist | harness | `harness checklist` at the cut | `cycles/NN/checklist.toml` |
| grade | graders, each in their own room, black box on the built product | one verdict per item, with evidence | `cycles/NN/verdicts-<grader>.toml` |
| score | harness | `harness score NN <verdicts...>` | `cycles/NN/score.toml` |
| scoreboard | harness | `harness scoreboard NN` | printed, filed in the cycle's record |
| review | reviewer | hunts invented rules and spec drift in the build; reads the diff, never grades | `cycles/NN/review.md` |
| accept | **owner** | on the evidence: the score, the scoreboard, the review, the builder's decisions; brings the build in or refuses it | `cycles/NN/cycle.toml`, `docs/decisions/` |

A builder never grades its own build; a grader never reads the builder's
tests; nobody re-records a golden to get a green (INV-3).

### Results are tool-neutral

The gate never reads a test runner; it reads **results**, written in
Allure's result model, one file per scenario run, by whatever tool ran it:
a Gherkin runner, a unit-test framework, a browser driver, a contract check,
a hand-run session. Each result carries the item it verifies as a label
(`item = item-003`), its status (`passed`, `failed`, `broken`, `skipped`),
its start and stop, and its **attachments**: a browser trace, a screenshot,
a request log, a terminal transcript, whatever the tool can keep. The
harness copies the run's results and attachments into the cycle
(`cycles/NN/results/run-N/`), so a grader, a reviewer and the owner see what
the suite saw, in one reader, whatever stack ran it (INV-11).

What verify holds a run to is read from the results alone: at least one
result was written; every result names an item on the checklist; every item
not tagged `@wip` has a result; no result for an item is anything but
`passed`. A suite whose runner exited 0 and wrote nothing is a refusal. The
same model carries the harness's own oracle: its goldens' comparisons are
written as results too, so one reader reads the judge and the judged.

## 8. Decisions

Planning and history are the same structure in opposite directions. A
decision selects one branch and prunes the rest; every divergence between a
plan item and what happened is a decision recorded in the plan's
`decisions.md` with the pruned branch named (Löyly, section 6). Decisions
about the harness itself go in `docs/decisions/`, numbered.

## 9. The harness's own custody

The harness is held still (INV-3). It is changed by a person, in a planning
round, against `harness/spec.feature` first: a behaviour change is a
scenario before it is code. Its gate is `cargo fmt --check`, `cargo clippy`,
`cargo test`, and its oracle's goldens once they exist, in the pre-commit
hook and in CI (INV-9). A golden changes for one of three reasons only
(`raw/loyly/harness/README.md`, the goldens), each named in the commit:

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

## 11. Where the rules are enforced

`docs/wow/invariants.md`: each rule with its enforcement point. How an agent
is pointed at this repository, and the guardrails it runs under:
`docs/wow/harness.md`. The domain the harness serves: `docs/wiki/`, from its
index.
