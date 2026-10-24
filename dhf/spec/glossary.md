# Glossary of the spec

The vocabulary of `dhf/decisions/adr-0002-spec-first-regeneration.md`. It
replaces `CONTEXT.md` at cutover; until then `CONTEXT.md` describes the current
record and this file describes the spec beside it. When a term here is resolved
or changes, this file changes in the same commit.

## The spec

**rdm-spec**: the product this spec describes: RDM rebuilt around this
decision. The suffix says what changed: the spec is the source and the code is
built from it. Until cutover the Python package and the `rdm` command keep
their name; `RDM` alone means today's implementation.

**Spec**: the source of truth for what the product does: the C4 model, the
event catalogue, the user needs, and the feature files the model's components
own. Everything under `dhf/spec/`. The code is built from it, never the other
way round.

**Scenario**: one requirement, written in Gherkin and accepted as a whole. Its
steps are its verification steps, not separate requirements.
_Avoid_: design input, acceptance criterion, test case.

**Scenario id**: the `@spec-NNNN` tag a scenario carries, handed out once by
the stamper and never reused. It says nothing about the scenario's wording or
location.

**Ledger**: `dhf/spec/ids.edn`, the highest id ever issued. Only the stamper
writes it.

**Rule**: a heading in a feature file grouping scenarios. It carries no id
and is not itself a requirement.

**Level**: how a scenario is verified, exactly one per scenario: `@e2e`,
`@contract`, `@example` or `@property`. There is no persona level.

**User need**: what a user requires, independent of any solution. Declared
in `dhf/spec/needs.edn`; every scenario traces to at least one.

**Planned**: a scenario tagged `@wip`. It is not run, it is planned whatever
any result says, the spec check accepts it and the release check refuses it.
There is no planning tool besides this.
_Avoid_: task, ticket, backlog item.

**Raw source**: what the spec is distilled from, never edited: for rdm-spec,
three repositories (`innolitics/rdm`, `scope-impact/agent-skills` and
`skallinen/loyly`), each recorded in `dhf/raw/` with the commit it is pinned
to. Cited as `<source>:<path>@<commit>`. Where a source and the spec disagree,
the source wins and the spec is the bug. A source's implementation is fenced:
never cited, and absent from the clean room.

**Domain wiki**: the domain distilled from the raw sources, `dhf/wiki/`: every
claim footnoted to a source, every page in the index, no implementation named.
The spec rests on it.
_Avoid_: documentation, notes.

## The design

**Event storm**: the design step that turns the spec's needs into domain
events, commands, actors, policies and aggregates, from which the bounded
contexts and components follow. Its board is a working product, not part of the spec.

**Bounded context**: a part of the domain with its own language and model,
and a composable module of the rdm-spec container: it can be regenerated alone
while the others stand. Drawn as a boundary around its components.

**Container**: something that runs or stores data (C4 level 2). rdm-spec has
one, the `rdm-spec` command (a modular monolith), beside the repository.

**Component**: a logical building block inside one bounded context (C4 level
3). Each owns one feature file, `dhf/spec/<context>/<component>.feature`.

**Event**: a domain event that passes between bounded contexts within one run,
declared in `dhf/spec/events.edn`. Steps that run at different times meet
through files instead.

**Event bus**: the in-process bus every event travels on, synchronous and in
subscription order. It refuses an event the catalogue does not declare.

**Skeleton**: what `rdm-spec init` writes and `rdm-spec adopt` adds: the spec,
the harness configuration, the raw, wiki and steps directories, decisions, the
pre-commit hook and the cut workflow.

**Kernel**: shared code every bounded context may use and that belongs to none
of them, such as the event bus. Specified under `dhf/spec/kernel/`, never drawn as
a container.

**Model**: `dhf/spec/model.edn`, the one C4 source. Every view is generated
from it.

**View**: a generated diagram of the model at one level: context, container,
component or dynamic. Never drawn by hand.

**Role**: who acts on the spec: engineer, reviewer, builder or spec owner.
Every role can be filled by a human or an agent, and the machinery treats both
the same. Two rules hold whoever fills them: a reviewer is not the author of
what is reviewed, and the builder of a cycle never reads its score.
_Avoid_: user (for a role), person (when either is meant).

**Builder**: the role that regenerates the build from a cut, inside a clean
room. A human or an agent.
_Avoid_: coding agent (as the role's name).

**Spec owner**: the role that reads the scoreboard and decides what the spec
must learn next. Never the builder of the cycle it reads.

## The harness

**Harness**: the containers that check the spec, ratify a build and score it.
In rdm-spec the harness is the product, so it is specified like every other
component, under `dhf/spec/`; only its configuration lives in
`dhf/harness/config.edn`.

**Step definition**: the code that executes a scenario's steps. It drives the
public surface only (the command, the files it reads and writes, and the
runner's step API) and survives every clean room.

**Oracle**: the Allure results of a run. A scenario is verified when a result
labelled with its id passed, and no result labelled with its id failed.

**Verified / failed / unverified / planned**: a scenario's status, derived
from the oracle: a passing result and none that did not pass; a result that did
not pass; no result at all; tagged `@wip`, whatever the results. An unverified
scenario counts as a fail when a cycle is scored.

**Cut**: a merged spec change tagged `spec-N`. Regeneration reads a cut and
nothing else.

**Fence**: the step that builds a clean room from a cut: one fresh commit
holding only the allowed paths (`:clean-room :allow`) and whatever
implementation its scope keeps.

**Scope**: what a regeneration rebuilds: the whole project, one container or
one component. The rest of the implementation stays in the clean room as it
was at the cut.

**Regeneration**: building the implementation again from a cut, inside the
fence.

**Spec check**: `rdm-spec check` on the spec: ids, levels, needs, citations,
the model's lint and the views' freshness.

**Ratify**: `rdm-spec ratify ROOM CYCLE`: the room's implementation, put into a
fresh checkout of the cut, passes the declared test command, writes results
and has black-box step definitions. Run by the installed rdm-spec, never by the
build under test.
_Avoid_: gate, on its own (say spec check or ratify).

**Cycle**: one regeneration and its score: `dhf/cycles/NN/cycle.edn` (cut,
scope, builder, spec owner, results, and `:bootstrap` for the very first),
with `checklist.json` and `score.edn` beside it.

**Scoreboard**: the comparison of two cycles over the items both scored,
counting what got better and what got worse, never a net.

**Gherkin runner**: the kernel code that runs scenarios against their step
definitions and writes Allure results labelled with each scenario's id.

**Cutover**: the moment the regenerated Clojure core, passing the whole spec
at a cut, takes over this repository from the current record.
