# Scope and container

## Löyly regenerates the whole app

Löyly's fence removes the whole implementation every cycle; the app is the
weekly experiment.[^five] Its retrospective reading of rdm-spec names scoped
regeneration as one of three departures that let builders guess: a scoped
cycle hid unrebuilt code for six cycles.[^retro]

## Why this harness scopes anyway

The intent (`README.md`) is regeneration by container and component, because
a product larger than a demo cannot be rebuilt whole every cycle, and because
a component rebuilt alone is the measurement of whether its spec carries it.
rdm-spec ran scoped cycles from cycle 05 and learned what makes them safe and
what makes them lie.

## How a scope went wrong

A scope read from where a rule is written misses what the rule reaches: one
rule in a parser's feature file changed how eight other components read the
spec, `scopes` named six, and the integrator added seven by hand.[^adr4] A
scope widened by hand is a scope remembered, which an earlier cycle had
already shown fails.[^adr4] rdm-spec's answer was to group the spec by slice
and make a change to any file of a slice rebuild every component the slice
reaches.[^adr4] The slice then became the unit of work as well, with its own
plan, status and decisions.[^adr5]

## What this harness keeps, and what it changes

Two things are kept from that history:

- **A scope is computed, never typed** (INV-4). The components whose
  scenarios changed between two cuts, closed over the events they publish
  and consume (publisher and every subscriber), up to their container.
  Event closure is what catches the parser case: a rule that changes what an
  event carries reaches everyone who reads it.
- **A plan is the unit of work; a component is the unit of regeneration.**
  rdm-spec's slices did both jobs and the two readings blurred.[^adr5] Here a
  plan may reach several components and a component several plans; the
  scope is read from the cut, never from the plan.

One thing is changed: there is no slice layout. The storm and the model
already say what a component is and which events bind it to others, so the
closure a slice was invented to approximate is computed from them.

## The whole-container cycle

A scoped cycle hides whatever it does not rebuild. rdm-spec's rule was a
whole-project cycle every fifth cycle,[^retro-next] and its two whole
rebuilds from nothing (cycles 24 and 25) are the strongest evidence the
method produced.[^adr5-cons] This harness makes the rule a refusal: the
configuration names the interval, `scopes` answers `whole-container = true`
when it is due or when a component moved between containers, and the
scoreboard names the cycle the next one is due.

[^five]: `raw/loyly/harness/README.md@17d68d2`, "The idea in five lines".
[^retro]: `raw/rdm/retrospective-01-12.md@a4641ae`, sections 1 and 3.
[^adr4]: `raw/rdm/decisions/adr-0004-slices.md@a4641ae`, "Context" and "Decision".
[^adr5]: `raw/rdm/decisions/adr-0005-slice-development.md@a4641ae`, "Decision".
[^retro-next]: `raw/rdm/retrospective-01-12.md@a4641ae`, "What follows for the next spec change", item 3.
[^adr5-cons]: `raw/rdm/decisions/adr-0005-slice-development.md@a4641ae`, "Consequences"; the scores are in rdm-spec's cycle records at `a4641ae`, not under `raw/`.
