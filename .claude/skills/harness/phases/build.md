# Phase: build

You are the one builder of this cycle, in the room `harness fence` made.
The room holds the cut and the implementation outside the scope, and
nothing of the scope's earlier implementation, no measurement, no golden.
What is not in the room does not exist.

## Read first, in the room

The plan, then the scenarios it delivers, then the wiki pages it cites,
then the model and the storm. Complete when you can name, for each ticket,
the scenario ids it must make green and the gaps marked `free` you may
decide.

## The loop

An externally tracked state, RED or GREEN, decides what you do next, and
overrides everything else:

- **GREEN**: write the next failing test from a scenario, named by its item
  id. Implement nothing. Do not look ahead.
- **RED**: write the minimum that passes. Write no new test.
- Refactor only when green; then commit.

A test written after the code that passes it is a failed round: revert
and take the round again. Log every transition in `redgreen.log`: state,
item id, commit.

**Every Nth round is a review round, not a build round** (N from
`spec/config.toml`, `[build] review-every`; 5 when unset). In it you write
no code and no test: you read your diff since the last review round
against the scenarios it claims, hunting a rule no scenario states, a Then
satisfied by a weaker check, a test written after its code. Each finding
is reverted or filed in `decisions.toml` before the next build round. Log
the round as `REVIEW` with its findings count.

**Tests drive the real interface** the way the actor in the scenario
would: the command, the page, the endpoint the Then observes, never an
internal function. A path the actor cannot reach is a finding against the
spec or the build, filed in `decisions.toml`, never forced open for the
test.

**A behaviour no scenario states is not built.** If a ticket needs a rule
the spec is silent on and the plan did not mark it `free`, you stop that
ticket, file the question in `decisions.toml` with `spec-gap = true`, and
move to the next ticket. The planner states it next round; "just this
once" is how a spec stops carrying its product.

## Results

Your suite writes one result per scenario run, in Allure's result model,
into the folder `spec/config.toml` names under `[verify] results`:
`{uuid}-result.json`, labels `item = <id>` and `tool = <what ran it>`,
status, start, stop, and the tool's attachments beside it. A test that
writes no result does not exist to the gate. Run the suite twice before
you claim green; the gate will.

## Decisions

Every choice on a `free` gap, and every divergence from the plan, goes in
the room's `decisions.toml`: the item, the choice, the branch you pruned,
why. A question on no `free` gap is not a choice you make: it is filed
with `spec-gap = true` and left unbuilt (above).

## Where you write

The scope's implementation paths and their tests, `redgreen.log`,
`decisions.toml`. Not `spec/`, not `docs/`, not `harness/`, not outside the
room. `verify` refuses a room whose spec tree differs from the cut's or
whose kept paths changed, so such an edit fails the gate; revert it and
take the round again.

## A look at the product, without a verdict

When the configuration sets `[build] look-every`, every such round you
open the product's real surface as the actor sees it (the attachments your
last run wrote: a screenshot at two widths, a transcript) and write three
lines in `redgreen.log` on what you see, as questions, not assertions.
Nobody judges them; they are there so that you look. Unset, the step does
not run: it costs rounds, and the budget is the owner's to set.

## Completion

Every scenario of every ticket has a result with status `passed` in both
runs, or is tagged `@wip`, or is listed in the report as not green with
the reason. `harness verify` is the state; your green is the claim.
