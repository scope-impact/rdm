# The event storm

## What a storm produces

An event storm finds the domain model with the people who know the domain.
rdm-spec's one storm produced: a table of actors, each with its kind (person
or external system) and what it wants;[^actors] a timeline of commands and
events in nine stages, from setting a project up through regeneration,
ratification and measurement to publishing;[^timeline] read models;
aggregates, each with the commands it handles and the invariants it
protects;[^aggregates] bounded contexts grouping the aggregates;[^contexts]
and hotspots.[^hotspots]

An aggregate is what must be checked together, in one step, for its rules to
hold.[^aggregates] That definition is what makes an aggregate the right thing
for a component to own: a component that owns an aggregate owns one place
where a rule is enforced, which is Löyly's INV-3 generalised (a rule enforced
in exactly one place, found by a grep that returns one site).[^inv3]

## Hotspots

A hotspot is a question the storm could not answer. Each needs a decision
before the features of the component it touches are written.[^hotspots]
rdm-spec's storm recorded fourteen, each naming what it touches; several were
decided by an agent rather than the owner and are marked so.[^hotspots] Two
are worth keeping as cautions:

- H12: when the harness is the product, the harness spec and the product
  spec are the same thing, and the harness's own scenarios move into the
  product's components.[^hotspots] That is the self-hosting this harness
  refuses (INV-3).
- H14: a cited source can leak the answer past the fence when the source
  holds an implementation. The decision was to remove that source and fence
  the others' code paths.[^hotspots] See [clean-room](clean-room.md).

## Why the storm is kept as data

Löyly keeps the storm's output as prose in the wiki, each claim footnoted, and
holds invented rules out by the minimum reference rule at review.[^inv1] rdm-spec
kept its storm as prose too, and over 27 cycles the storm was held once, the
model was then edited directly, and the glossary came to say the storm is "a
way of working, kept as prose when kept at all, never part of the
spec".[^drift] Nothing refused that drift, because nothing read the storm.

This harness keeps the storm as data (`spec/storm.toml`) so that a tool can
hold the model to it: a component must own storm elements; every relationship
between contexts must carry a storm event; an event the storm holds must be
carried by some relationship; an open hotspot refuses a cut (INV-1). The
storm as data is what lets the opinion be a refusal rather than a convention.

## Where the storm's rules come from

The storm cites sources, and the sources are immutable. Every behavioural
rule traces to a wiki page, and every wiki page to a source under `raw/`; if
a rule cannot be traced it does not go in, it goes back to the
interview.[^inv1] Löyly's own first corpus was invented scaffolding plausible
enough to fool a reader, and this rule plus the purge that followed are what
removed it.[^inv1] This harness enforces the storm's half of the rule by tool
(`harness storm`: every element cites a source under `raw/`, and the file
must exist) and the wiki's half by review.

[^actors]: `raw/rdm/storm/2026-10-24-rdm.md@a4641ae`, "Actors".
[^timeline]: `raw/rdm/storm/2026-10-24-rdm.md@a4641ae`, "Timeline", stages 1 to 9.
[^aggregates]: `raw/rdm/storm/2026-10-24-rdm.md@a4641ae`, "Aggregates".
[^contexts]: `raw/rdm/storm/2026-10-24-rdm.md@a4641ae`, "Bounded contexts".
[^hotspots]: `raw/rdm/storm/2026-10-24-rdm.md@a4641ae`, "Hotspots", H1 to H14.
[^inv3]: `raw/loyly/docs/wow/invariants.md@17d68d2`, INV-3.
[^inv1]: `raw/loyly/docs/wow/invariants.md@17d68d2`, INV-1.
[^drift]: `raw/rdm/decisions/adr-0003-core-and-plugins.md@a4641ae` (the domain model moved out of the core) and `raw/rdm/decisions/adr-0004-slices.md@a4641ae` (the model's spec paths following the layout, not the storm); the glossary line is quoted from rdm-spec at `a4641ae` and is not under `raw/`, so it is a claim of this page's author, recorded in `docs/decisions/0001-clean-room-rewrite.md`.
