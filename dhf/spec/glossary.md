# Glossary of the spec

The vocabulary of `dhf/decisions/adr-0002-spec-first-regeneration.md`. It
replaces `CONTEXT.md` at cutover; until then `CONTEXT.md` describes the current
record and this file describes the spec beside it. When a term here is resolved
or changes, this file changes in the same commit.

## The spec

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
`@contract`, `@example`, `@property` or `@persona`.

**User need**: what a user requires, independent of any solution. Declared
in `dhf/spec/needs.edn`; every scenario traces to at least one.

**Planned**: a scenario tagged `@wip`. The gate accepts it, the release check
refuses it. There is no planning tool besides this.
_Avoid_: task, ticket, backlog item.

## The design

**Event storm**: the design step that turns the spec's needs into domain
events, commands, actors, policies and aggregates, from which the containers
and components follow. Its board is a working product, not part of the spec.

**Bounded context**: a part of the domain with its own language and model.
Each one is exactly one container.

**Container**: something that runs or stores data (C4 level 2). Here, one per
bounded context, each with its own entry point and black-box contract.

**Component**: a logical building block inside one container (C4 level 3).
Each owns one feature file, `dhf/spec/<container>/<component>.feature`, and
its design prose beside it.

**Event**: a domain event that crosses a container boundary, declared in
`dhf/spec/events.edn`. Events are the contracts between containers.

**Model**: `dhf/spec/model.edn`, the one C4 source. Every view is generated
from it.

**View**: a generated diagram of the model at one level: context, container,
component or dynamic. Never drawn by hand.

## The harness

**Harness**: the tools that check the spec, ratify a build and score it,
specified in `dhf/harness/spec.feature`.

**Step definition**: the code that executes a scenario's steps. It drives the
public surface only (the command, its files, its events) and survives the
fence.

**Oracle**: the Allure results of a run. A scenario is verified when a result
labelled with its id passed, and no result labelled with its id failed.

**Verified / failed / unverified**: a scenario's status, derived from the
oracle: a passing result; a result that did not pass; no result at all. An
unverified scenario counts as a fail when a cycle is scored.

**Cut**: a merged spec change tagged `spec-N`. Regeneration reads a cut and
nothing else.

**Fence**: the step that builds a clean room from a cut, with every
implementation and every measurement deleted from its tree and its history,
and proves it.

**Regeneration**: building the implementation again from a cut, inside the
fence.

**Gate**: the check that ratifies a build: the declared test command ran,
wrote results, and the thing answering is this project.

**Cycle**: one regeneration and its score.

**Scoreboard**: the comparison of two cycles over the items both scored,
counting what got better and what got worse, never a net.

**Judge**: the pinned previous release that gates and scores a regenerated
RDM.

**Cutover**: the moment the regenerated Clojure core, passing the whole spec
at a cut, takes over this repository from the current record.
