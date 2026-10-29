# Phase: plan

You write or update a plan and its tickets. You build nothing, and you
read no implementation: not the room, not `impl/`, not a previous build's
tests. If a question can only be answered by looking at code, it is a gap.

## Steps

1. **Scope the plan.** Name the goal in one sentence, the wiki pages it
   rests on (at least one; a plan with none is refused at review), and the
   scenarios it delivers by item id. Complete when every scenario named
   exists in a feature file at the cut and is stamped.
2. **Storm first.** For each behaviour the plan adds, find its actor,
   command, event, aggregate in `spec/storm.toml`. Missing: write the
   element with its source under `raw/`, and open a hotspot for any
   question you could not source. Complete when `harness storm` passes on
   your draft, or every refusal it prints is a hotspot you opened on
   purpose.
3. **Model next.** Name the component that owns each new element, under
   its container, in its group. Complete when `harness model` passes.
4. **State the gaps.** Start from the instrument: `cycles/*/posterior.toml`
   ranks items by P(gap) from past cycles; every item above the threshold
   in your plan's components is a gap you must state or argue down. Then
   read every scenario the plan delivers as a grader would: for each place a grader could see an answer the spec does not
   state, write a **gap** with its scenario, the question, and a mark:
   - `visible`: the spec must state it before the build; propose the
     scenario text;
   - `free`: the builder may choose; name what any choice must still
     satisfy.
   Complete when every scenario has been read for gaps and the list is
   empty or every entry is marked. Thirty gaps in a new slice is normal;
   zero is suspicious.
5. **Tickets.** Break the plan into tickets, each naming the scenarios it
   satisfies, in the order a builder should take them so each lands once.
   Complete when every delivered scenario is in exactly one ticket.
6. **Placeholders.** Any scenario whose Then holds a value the spec cannot
   pin is tagged `@nondeterministic`, the value written `<what it is>`, and
   its shape or judge declared in `spec/placeholders.toml`.

## Where you write

`docs/plan/NN-slug/plan.md`, `ticket-NN-*.md`, `decisions.md`; `spec/`
(storm, model, feature files, placeholders) as drafts for the owner. Nowhere
else.

## Completion

`harness check` passes on your draft, or every refusal is a hotspot or a
visible gap you listed. The plan's status is `planned`. The report lists the
gaps first; the owner accepts, changes or strikes each before a cut.
