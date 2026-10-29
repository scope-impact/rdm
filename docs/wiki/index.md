# The harness's domain wiki

The domain this harness serves is not a product's; it is the practice of
regenerating a product from its spec. This wiki is that domain, distilled
from the sources under `raw/`. **Sources are authoritative; the wiki is
derived.** Where a page and a source disagree, the source wins and the page
is the bug (`raw/loyly/README.md`, "How to read the repo").

Every factual claim carries a footnote naming the source file and the
section, pinned by commit. A claim with no footnote is not finished, it is
filed. Where sources conflict, the page says so and records which one the
harness follows and why.

## The sources

| Source | What it is | Weight |
|---|---|---|
| `raw/loyly/` at `17d68d2` | Löyly: the method's own statement (README, way of working, invariants, agent harness) and its harness's README and spec | the method, as its author states it |
| `raw/rdm/` at `a4641ae` | rdm-spec: its way of working after 27 cycles, five decisions, one retrospective, one event storm, its events and needs | what happened when the method was applied to a harness, and what it cost |

## The pages

| Page | What it settles |
|---|---|
| [the-method](pages/the-method.md) | the five mechanisms; the spec as the asset and code as a build product; harness versus instrument |
| [event-storm](pages/event-storm.md) | what a storm produces, why it is kept as data, why the harness forces it |
| [scope-and-container](pages/scope-and-container.md) | the unit of regeneration; how a scope is computed; the whole-container cycle |
| [clean-room](pages/clean-room.md) | the fence, its proof, and what leaks past it |
| [gate-and-instrument](pages/gate-and-instrument.md) | "done" ratified; the derived checklist; honest denominators; the goldens |

## Vocabulary

The names below appear at every layer, re-cased per layer. A term here, in
the storm, and in a feature file must agree.

| Term | Meaning |
|---|---|
| **storm** | the event storm kept as data: actors, commands, events, policies, aggregates, read models, hotspots; the front of the chain |
| **hotspot** | a question the storm could not answer; open until decided with a decision and a source |
| **model** | containers, components and their relationships, derived from the storm |
| **component** | the unit of regeneration: owns storm elements and one feature file, sits in one container |
| **container** | something that runs or stores; the ceiling of a scope |
| **cut** | a tag on a merged spec that `check` passed; a regeneration reads it and nothing else |
| **scope** | the components a cycle rebuilds, computed from two cuts |
| **room** | the clean room: a clone of the cut with the scope's implementation and every measurement removed, and the removal proved |
| **gate** | `verify`: the script the builder does not control that ratifies a build |
| **instrument** | checklist, score and scoreboard: what reports to a person and never into the loop |
| **golden** | a recorded output of the harness's own tools, held byte for byte by its oracle |
| **cycle** | one pass: cut, scope, fence, build, gate, grade, score, scoreboard, accept |
| **owner** | the spec owner: the person whose decisions a cycle waits on |
